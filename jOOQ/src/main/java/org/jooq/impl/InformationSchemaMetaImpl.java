/*
 * Copyright (c) 2009-2016, Data Geekery GmbH (http://www.datageekery.com)
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

import static java.util.Collections.unmodifiableList;
import static org.jooq.impl.DSL.name;
import static org.jooq.util.xml.jaxb.TableConstraintType.PRIMARY_KEY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jooq.Catalog;
import org.jooq.Configuration;
import org.jooq.DataType;
import org.jooq.ForeignKey;
import org.jooq.Meta;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Schema;
import org.jooq.Sequence;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.exception.SQLDialectNotSupportedException;
import org.jooq.util.xml.jaxb.Column;
import org.jooq.util.xml.jaxb.InformationSchema;
import org.jooq.util.xml.jaxb.KeyColumnUsage;
import org.jooq.util.xml.jaxb.TableConstraint;

/**
 * @author Lukas Eder
 */
final class InformationSchemaMetaImpl implements Meta {

    private final Configuration                             configuration;
    private final List<Catalog>                             catalogs;
    private final List<Schema>                              schemas;
    private final Map<Name, Schema>                         schemasByName;
    private final Map<Catalog, List<Schema>>                schemasPerCatalog;
    private final List<InformationSchemaTable>              tables;
    private final Map<Name, InformationSchemaTable>         tablesByName;
    private final Map<Schema, List<InformationSchemaTable>> tablesPerSchema;
    private final List<Sequence<?>>                         sequences;
    private final Map<Schema, List<Sequence<?>>>            sequencesPerSchema;
    private final List<UniqueKey<?>>                        primaryKeys;

    InformationSchemaMetaImpl(Configuration configuration, InformationSchema schema) {
        this.configuration = configuration;
        this.catalogs = new ArrayList<Catalog>();
        this.schemas = new ArrayList<Schema>();
        this.schemasByName = new HashMap<Name, Schema>();
        this.schemasPerCatalog = new HashMap<Catalog, List<Schema>>();
        this.tables = new ArrayList<InformationSchemaTable>();
        this.tablesByName = new HashMap<Name, InformationSchemaTable>();
        this.tablesPerSchema = new HashMap<Schema, List<InformationSchemaTable>>();
        this.sequences = new ArrayList<Sequence<?>>();
        this.sequencesPerSchema = new HashMap<Schema, List<Sequence<?>>>();
        this.primaryKeys = new ArrayList<UniqueKey<?>>();

        init(schema);
    }

    private final void init(InformationSchema meta) {

        // Initialise base collections
        for (org.jooq.util.xml.jaxb.Schema xs : meta.getSchemata()) {
            InformationSchemaCatalog catalog = new InformationSchemaCatalog(xs.getCatalogName());

            if (!catalogs.contains(catalog)) {
				catalogs.add(catalog);
			}

            InformationSchemaSchema is = new InformationSchemaSchema(xs.getSchemaName(), catalog);
            schemas.add(is);
            schemasByName.put(name(xs.getCatalogName(), xs.getSchemaName()), is);
        }

        for (org.jooq.util.xml.jaxb.Table xt : meta.getTables()) {
            InformationSchemaTable it = new InformationSchemaTable(xt.getTableName(), schemasByName.get(name(xt.getTableCatalog(), xt.getTableSchema())));
            tables.add(it);
            tablesByName.put(name(xt.getTableCatalog(), xt.getTableSchema(), xt.getTableName()), it);
        }

        List<Column> columns = new ArrayList<Column>(meta.getColumns());
        Collections.sort(columns, new Comparator<Column>() {
            @Override
            public int compare(Column o1, Column o2) {
                Integer p1 = o1.getOrdinalPosition();
                Integer p2 = o2.getOrdinalPosition();

                if (p1 == p2) {
					return 0;
				}
                if (p1 == null) {
					return -1;
				}
                if (p2 != null) {
					return p1.compareTo(p2);
				}

                return 1;
            }
        });

        for (Column xc : columns) {
            String typeName = xc.getDataType();
            int length = xc.getCharacterMaximumLength() == null ? 0 : xc.getCharacterMaximumLength();
            int precision = xc.getNumericPrecision() == null ? 0 : xc.getNumericPrecision();
            int scale = xc.getNumericScale() == null ? 0 : xc.getNumericScale();
            boolean nullable = xc.isIsNullable() == null ? true : xc.isIsNullable();

            // TODO: Exception handling should be moved inside SQLDataType
            AbstractTable.createField(
                xc.getColumnName(),
                type(typeName, length, precision, scale, nullable),
                tablesByName.get(name(xc.getTableCatalog(), xc.getTableSchema(), xc.getTableName()))
            );
        }

        Map<Name, List<TableField<Record, ?>>> columnsByConstraint = new HashMap<Name, List<TableField<Record, ?>>>();
        List<KeyColumnUsage> keyColumnUsages = new ArrayList<KeyColumnUsage>(meta.getKeyColumnUsages());
        Collections.sort(keyColumnUsages, new Comparator<KeyColumnUsage>() {
            @Override
            public int compare(KeyColumnUsage o1, KeyColumnUsage o2) {
                Integer p1 = o1.getOrdinalPosition();
                Integer p2 = o2.getOrdinalPosition();

                if (p1 == p2) {
					return 0;
				}
                if (p1 == null) {
					return -1;
				}
                if (p2 != null) {
					return p1.compareTo(p2);
				}

                return 1;
            }
        });

        for (KeyColumnUsage xc : keyColumnUsages) {
            Name name = name(xc.getConstraintCatalog(), xc.getConstraintSchema(), xc.getConstraintName());
            List<TableField<Record, ?>> fields = columnsByConstraint.get(name);

            if (fields == null) {
                fields = new ArrayList<TableField<Record, ?>>();
                columnsByConstraint.put(name, fields);
            }

            InformationSchemaTable table = tablesByName.get(name(xc.getTableCatalog(), xc.getTableSchema(), xc.getTableName()));
            fields.add((TableField<Record, ?>) table.field(xc.getColumnName()));
        }

        for (TableConstraint xc : meta.getTableConstraints()) {
            switch (xc.getConstraintType()) {
                case PRIMARY_KEY:
                case UNIQUE: {
                    InformationSchemaTable table = tablesByName.get(name(xc.getTableCatalog(), xc.getTableSchema(), xc.getTableName()));
                    List<TableField<Record, ?>> c = columnsByConstraint.get(name(xc.getConstraintCatalog(), xc.getConstraintSchema(), xc.getConstraintName()));
                    UniqueKey<Record> key = AbstractKeys.createUniqueKey(table, xc.getConstraintName(), c.toArray(new TableField[0]));

                    if (xc.getConstraintType() == PRIMARY_KEY) {
                        table.primaryKey = key;
                        primaryKeys.add(key);
                    }

                    table.uniqueKeys.add(key);

                    break;
                }
            }
        }

        for (org.jooq.util.xml.jaxb.Sequence xs : meta.getSequences()) {
            String typeName = xs.getDataType();
            int length = xs.getCharacterMaximumLength() == null ? 0 : xs.getCharacterMaximumLength();
            int precision = xs.getNumericPrecision() == null ? 0 : xs.getNumericPrecision();
            int scale = xs.getNumericScale() == null ? 0 : xs.getNumericScale();
            boolean nullable = true;

            @SuppressWarnings({ "rawtypes", "unchecked" })
            InformationSchemaSequence is = new InformationSchemaSequence(
                xs.getSequenceName(),
                schemasByName.get(name(xs.getSequenceCatalog(), xs.getSequenceSchema())),
                type(typeName, length, precision, scale, nullable)
            );

            sequences.add(is);
        }

        // Initialise indexes
        for (Schema s : schemas) {
            Catalog c = s.getCatalog();
            List<Schema> list = schemasPerCatalog.get(c);

            if (list == null) {
                list = new ArrayList<Schema>();
                schemasPerCatalog.put(c, list);
            }

            list.add(s);
        }

        for (InformationSchemaTable t : tables) {
            Schema s = t.getSchema();
            List<InformationSchemaTable> list = tablesPerSchema.get(s);

            if (list == null) {
                list = new ArrayList<InformationSchemaTable>();
                tablesPerSchema.put(s, list);
            }

            list.add(t);
        }

        for (Sequence<?> q : sequences) {
            Schema s = q.getSchema();
            List<Sequence<?>> list = sequencesPerSchema.get(s);

            if (list == null) {
                list = new ArrayList<Sequence<?>>();
                sequencesPerSchema.put(s, list);
            }

            list.add(q);
        }
    }

    private final DataType<?> type(String typeName, int length, int precision, int scale, boolean nullable) {
        DataType<?> type = null;

        try {
            type = DefaultDataType.getDataType(configuration.family(), typeName);
            type = type.nullable(nullable);

            if (length != 0) {
				type = type.length(length);
			} else if (precision != 0 || scale != 0) {
				type = type.precision(precision, scale);
			}
        }
        catch (SQLDialectNotSupportedException e) {
            type = SQLDataType.OTHER;
        }

        return type;
    }

    @Override
    public final List<Catalog> getCatalogs() {
        return unmodifiableList(catalogs);
    }

    @Override
    public final List<Schema> getSchemas() {
        return unmodifiableList(schemas);
    }

    @Override
    public final List<Table<?>> getTables() {
        return unmodifiableList(tables);
    }

    @Override
    public final List<Sequence<?>> getSequences() {
        return unmodifiableList(sequences);
    }

    @Override
    public final List<UniqueKey<?>> getPrimaryKeys() {
        return unmodifiableList(primaryKeys);
    }

    private final class InformationSchemaCatalog extends CatalogImpl {

        /**
         * Generated UID.
         */
        private static final long serialVersionUID = 87038321849045492L;

        InformationSchemaCatalog(String name) {
            super(name);
        }

        @Override
        public final List<Schema> getSchemas() {
            return unmodifiableList(schemasPerCatalog.get(this));
        }
    }

    private final class InformationSchemaSchema extends SchemaImpl {

        /**
         * Generated UID.
         */
        private static final long serialVersionUID = 7290709749127378187L;

        public InformationSchemaSchema(String name, Catalog catalog) {
            super(name, catalog);
        }

        @Override
        public final List<Table<?>> getTables() {
            return unmodifiableList(tablesPerSchema.get(this));
        }

        @Override
        public final List<Sequence<?>> getSequences() {
            return unmodifiableList(sequencesPerSchema.get(this));
        }
    }

    private final class InformationSchemaTable extends TableImpl<Record> {

        /**
         * Generated UID.
         */
        private static final long serialVersionUID = 4314110578549768267L;

        UniqueKey<Record>         primaryKey;
        List<UniqueKey<Record>>   uniqueKeys       = new ArrayList<UniqueKey<Record>>();

        public InformationSchemaTable(String name, Schema schema) {
            super(name, schema);
        }

        @Override
        public UniqueKey<Record> getPrimaryKey() {
            return primaryKey;
        }

        @Override
        public List<UniqueKey<Record>> getKeys() {
            return Collections.unmodifiableList(uniqueKeys);
        }

        @Override
        public List<ForeignKey<Record, ?>> getReferences() {
            return super.getReferences();
        }
    }

    private final class InformationSchemaSequence<N extends Number> extends SequenceImpl<N> {

        /**
         * Generated UID.
         */
        private static final long serialVersionUID = -1246697252597049756L;

        InformationSchemaSequence(String name, Schema schema, DataType<N> type) {
            super(name, schema, type);
        }
    }
}
