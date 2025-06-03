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

/**
 * Represents foreign key relationship metadata for GraphQL schema generation.
 */
public class ForeignKeyInfo {
    
    /** The name of the column in the local table that references another table */
    private String columnName;
    
    /** The name of the referenced table */
    private String referencedTable;
    
    /** The name of the column in the referenced table */
    private String referencedColumn;

    /**
     * Constructs a new ForeignKeyInfo with all relationship details.
     * 
     * @param columnName the name of the local column that references another table
     * @param referencedTable the name of the referenced table
     * @param referencedColumn the name of the column in the referenced table
     */
    public ForeignKeyInfo(String columnName, String referencedTable, String referencedColumn) {
        this.columnName = columnName;
        this.referencedTable = referencedTable;
        this.referencedColumn = referencedColumn;
    }

    public ForeignKeyInfo() {
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public void setReferencedTable(String referencedTable) {
        this.referencedTable = referencedTable;
    }

    public String getReferencedColumn() {
        return referencedColumn;
    }

    public void setReferencedColumn(String referencedColumn) {
        this.referencedColumn = referencedColumn;
    }
}