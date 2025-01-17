/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.table.source;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.manifest.PartitionEntry;
import org.apache.paimon.operation.DefaultValueAssigner;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.table.source.snapshot.SnapshotReader;
import org.apache.paimon.table.source.snapshot.StartingScanner;
import org.apache.paimon.table.source.snapshot.StartingScanner.ScannedResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.apache.paimon.CoreOptions.MergeEngine.FIRST_ROW;

/** {@link TableScan} implementation for batch planning. */
public class DataTableBatchScan extends AbstractDataTableScan {

    private final DefaultValueAssigner defaultValueAssigner;

    private StartingScanner startingScanner;
    private boolean hasNext;

    private Integer pushDownLimit;

    public DataTableBatchScan(
            boolean pkTable,
            CoreOptions options,
            SnapshotReader snapshotReader,
            DefaultValueAssigner defaultValueAssigner) {
        super(options, snapshotReader);
        this.hasNext = true;
        this.defaultValueAssigner = defaultValueAssigner;
        if (pkTable && (options.deletionVectorsEnabled() || options.mergeEngine() == FIRST_ROW)) {
            snapshotReader.withLevelFilter(level -> level > 0);
        }
    }

    @Override
    public InnerTableScan withFilter(Predicate predicate) {
        snapshotReader.withFilter(defaultValueAssigner.handlePredicate(predicate));
        return this;
    }

    @Override
    public InnerTableScan withLimit(int limit) {
        this.pushDownLimit = limit;
        return this;
    }

    @Override
    public TableScan.Plan plan() {
        if (startingScanner == null) {
            startingScanner = createStartingScanner(false);
        }

        if (hasNext) {
            hasNext = false;
            StartingScanner.Result result = startingScanner.scan(snapshotReader);
            StartingScanner.Result limitedResult = applyPushDownLimit(result);
            return DataFilePlan.fromResult(limitedResult);
        } else {
            throw new EndOfScanException();
        }
    }

    @Override
    public List<PartitionEntry> listPartitionEntries() {
        if (startingScanner == null) {
            startingScanner = createStartingScanner(false);
        }
        return startingScanner.scanPartitions(snapshotReader);
    }

    private StartingScanner.Result applyPushDownLimit(StartingScanner.Result result) {
        if (pushDownLimit != null && result instanceof ScannedResult) {
            long scannedRowCount = 0;
            SnapshotReader.Plan plan = ((ScannedResult) result).plan();
            List<DataSplit> splits = plan.dataSplits();
            if (splits.isEmpty()) {
                return result;
            }

            // We first add the rawConvertible split to avoid merging, and if the row count
            // is still less than limit number, then add split which is not rawConvertible.
            splits.sort(
                    (x, y) -> {
                        if (x.rawConvertible() && y.rawConvertible()) {
                            return 0;
                        } else if (x.rawConvertible()) {
                            return -1;
                        } else {
                            return 1;
                        }
                    });

            // fast return if there is no rawConvertible split
            if (!splits.get(0).rawConvertible()) {
                return result;
            }

            List<Split> limitedSplits = new ArrayList<>();
            for (DataSplit dataSplit : splits) {
                long splitRowCount = getRowCountForSplit(dataSplit);
                limitedSplits.add(dataSplit);
                scannedRowCount += splitRowCount;
                if (scannedRowCount >= pushDownLimit) {
                    break;
                }
            }

            SnapshotReader.Plan newPlan =
                    new PlanImpl(plan.watermark(), plan.snapshotId(), limitedSplits);
            return new ScannedResult(newPlan);
        } else {
            return result;
        }
    }

    /**
     * 0 represents that we can't compute the row count of this split: 1. the split needs to be
     * merged; 2. the table enabled deletion vector and there are some deletion files.
     */
    private long getRowCountForSplit(DataSplit split) {
        if (split.deletionFiles().isPresent()
                && split.deletionFiles().get().stream().anyMatch(Objects::nonNull)) {
            return 0L;
        }
        return split.convertToRawFiles()
                .map(files -> files.stream().map(RawFile::rowCount).reduce(Long::sum).orElse(0L))
                .orElse(0L);
    }

    @Override
    public DataTableScan withShard(int indexOfThisSubtask, int numberOfParallelSubtasks) {
        snapshotReader.withShard(indexOfThisSubtask, numberOfParallelSubtasks);
        return this;
    }
}
