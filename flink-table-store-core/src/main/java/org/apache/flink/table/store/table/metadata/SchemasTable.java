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

package org.apache.flink.table.store.table.metadata;

import org.apache.flink.core.fs.Path;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.GenericMapData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.utils.IteratorRecordReader;
import org.apache.flink.table.store.file.utils.RecordReader;
import org.apache.flink.table.store.file.utils.SerializationUtils;
import org.apache.flink.table.store.table.Table;
import org.apache.flink.table.store.table.source.Split;
import org.apache.flink.table.store.table.source.TableRead;
import org.apache.flink.table.store.table.source.TableScan;
import org.apache.flink.table.store.utils.ProjectedRowData;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;

import org.apache.flink.shaded.guava30.com.google.common.collect.Iterators;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.flink.table.store.file.catalog.Catalog.METADATA_TABLE_SPLITTER;

/** A {@link Table} for showing schemas of table. */
public class SchemasTable implements Table {

    private static final long serialVersionUID = 1L;

    public static final String SCHEMAS = "schemas";

    public static final RowType TABLE_TYPE =
            new RowType(
                    Arrays.asList(
                            new RowType.RowField("schema_id", new BigIntType(false)),
                            new RowType.RowField(
                                    "fields",
                                    new ArrayType(
                                            new RowType(
                                                    Arrays.asList(
                                                            new RowType.RowField(
                                                                    "id", new IntType(false)),
                                                            new RowType.RowField(
                                                                    "name",
                                                                    SerializationUtils
                                                                            .newStringType(false)),
                                                            new RowType.RowField(
                                                                    "type",
                                                                    SerializationUtils
                                                                            .newStringType(false)),
                                                            new RowType.RowField(
                                                                    "description",
                                                                    SerializationUtils
                                                                            .newStringType(
                                                                                    true)))))),
                            new RowType.RowField(
                                    "partition_keys",
                                    new ArrayType(SerializationUtils.newStringType(false))),
                            new RowType.RowField(
                                    "primary_keys",
                                    new ArrayType(SerializationUtils.newStringType(false))),
                            new RowType.RowField(
                                    "options",
                                    new MapType(
                                            SerializationUtils.newStringType(false),
                                            SerializationUtils.newStringType(false))),
                            new RowType.RowField(
                                    "comment", SerializationUtils.newStringType(true))));

    private final Path location;

    public SchemasTable(Path location) {
        this.location = location;
    }

    @Override
    public String name() {
        return location.getName() + METADATA_TABLE_SPLITTER + SCHEMAS;
    }

    @Override
    public RowType rowType() {
        return TABLE_TYPE;
    }

    @Override
    public TableScan newScan() {
        return new SchemasScan();
    }

    @Override
    public TableRead newRead() {
        return new SchemasRead();
    }

    private class SchemasScan implements TableScan {

        @Override
        public TableScan withFilter(Predicate predicate) {
            return this;
        }

        @Override
        public Plan plan() {
            return () -> Collections.singletonList(new SchemasSplit(location));
        }
    }

    /** {@link Split} implementation for {@link SchemasTable}. */
    private static class SchemasSplit implements Split {

        private static final long serialVersionUID = 1L;

        private final Path location;

        private SchemasSplit(Path location) {
            this.location = location;
        }

        @Override
        public long rowCount() {
            return new SchemaManager(location).listAllIds().size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SchemasSplit that = (SchemasSplit) o;
            return Objects.equals(location, that.location);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location);
        }
    }

    /** {@link TableRead} implementation for {@link SchemasTable}. */
    private static class SchemasRead implements TableRead {

        private int[][] projection;

        @Override
        public TableRead withFilter(Predicate predicate) {
            return this;
        }

        @Override
        public TableRead withProjection(int[][] projection) {
            this.projection = projection;
            return this;
        }

        @Override
        public RecordReader<RowData> createReader(Split split) throws IOException {
            if (!(split instanceof SchemasSplit)) {
                throw new IllegalArgumentException("Unsupported split: " + split.getClass());
            }
            Path location = ((SchemasSplit) split).location;
            Iterator<TableSchema> schemas = new SchemaManager(location).listAll().iterator();
            Iterator<RowData> rows = Iterators.transform(schemas, this::toRow);
            if (projection != null) {
                rows =
                        Iterators.transform(
                                rows, row -> ProjectedRowData.from(projection).replaceRow(row));
            }
            return new IteratorRecordReader<>(rows);
        }

        private RowData toRow(TableSchema schema) {
            List<GenericMapData> fields = new ArrayList<>(schema.fields().size());
            schema.fields()
                    .forEach(
                            f -> {
                                Map<StringData, StringData> field = new LinkedHashMap<>(4);
                                field.put(
                                        StringData.fromString("id"),
                                        StringData.fromString(String.valueOf(f.id())));
                                field.put(
                                        StringData.fromString("name"),
                                        StringData.fromString(f.name()));
                                field.put(
                                        StringData.fromString("type"),
                                        StringData.fromString(f.type().logicalType().toString()));
                                field.put(
                                        StringData.fromString("description"),
                                        StringData.fromString(f.description()));
                                fields.add(new GenericMapData(field));
                            });
            Map<StringData, StringData> options = new HashMap<>(schema.options().size());
            schema.options()
                    .forEach(
                            (k, v) ->
                                    options.put(
                                            StringData.fromString(k), StringData.fromString(v)));
            return GenericRowData.of(
                    schema.id(),
                    new GenericArrayData(
                            schema.fields().stream()
                                    .map(
                                            f ->
                                                    GenericRowData.of(
                                                            f.id(),
                                                            StringData.fromString(f.name()),
                                                            StringData.fromString(
                                                                    f.type()
                                                                            .logicalType()
                                                                            .toString()),
                                                            StringData.fromString(f.description())))
                                    .toArray()),
                    new GenericArrayData(
                            schema.partitionKeys().stream().map(StringData::fromString).toArray()),
                    new GenericArrayData(
                            schema.primaryKeys().stream().map(StringData::fromString).toArray()),
                    new GenericMapData(options),
                    StringData.fromString(schema.comment()));
        }
    }
}
