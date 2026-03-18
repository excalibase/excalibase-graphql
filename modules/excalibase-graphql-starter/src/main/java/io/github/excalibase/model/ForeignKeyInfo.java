/*
 * Copyright 2025 Excalibase Team and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.excalibase.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents foreign key relationship metadata for GraphQL schema generation.
 * Supports both simple (single-column) and composite (multi-column) foreign keys.
 */
public class ForeignKeyInfo {

    /** Ordered list of FK column names in the local table */
    private List<String> columnNames = new ArrayList<>();

    /** The name of the referenced table */
    private String referencedTable;

    /** Ordered list of column names in the referenced table (same order as columnNames) */
    private List<String> referencedColumns = new ArrayList<>();

    public ForeignKeyInfo() {
    }

    /** Convenience constructor for simple single-column foreign keys. */
    public ForeignKeyInfo(String columnName, String referencedTable, String referencedColumn) {
        this.columnNames = new ArrayList<>(List.of(columnName));
        this.referencedTable = referencedTable;
        this.referencedColumns = new ArrayList<>(List.of(referencedColumn));
    }

    /** Constructor for composite foreign keys. */
    public ForeignKeyInfo(List<String> columnNames, String referencedTable, List<String> referencedColumns) {
        this.columnNames = new ArrayList<>(columnNames);
        this.referencedTable = referencedTable;
        this.referencedColumns = new ArrayList<>(referencedColumns);
    }

    // --- List accessors (primary API) ---

    public List<String> getColumnNames() {
        return columnNames;
    }

    public void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    public List<String> getReferencedColumns() {
        return referencedColumns;
    }

    public void setReferencedColumns(List<String> referencedColumns) {
        this.referencedColumns = referencedColumns;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public void setReferencedTable(String referencedTable) {
        this.referencedTable = referencedTable;
    }

    // --- Convenience accessors for single-column FK (first element) ---

    /** Returns the first FK column name. Use getColumnNames() for composite FKs. */
    public String getColumnName() {
        return columnNames.isEmpty() ? null : columnNames.getFirst();
    }

    public void setColumnName(String columnName) {
        if (this.columnNames.isEmpty()) {
            this.columnNames.add(columnName);
        } else {
            this.columnNames.set(0, columnName);
        }
    }

    /** Returns the first referenced column name. Use getReferencedColumns() for composite FKs. */
    public String getReferencedColumn() {
        return referencedColumns.isEmpty() ? null : referencedColumns.getFirst();
    }

    public void setReferencedColumn(String referencedColumn) {
        if (this.referencedColumns.isEmpty()) {
            this.referencedColumns.add(referencedColumn);
        } else {
            this.referencedColumns.set(0, referencedColumn);
        }
    }

    /** Returns true if this FK spans more than one column. */
    public boolean isComposite() {
        return columnNames.size() > 1;
    }
}
