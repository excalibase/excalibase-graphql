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
 * Represents database column metadata for GraphQL schema generation.
 */
public class ColumnInfo {
    
    /** The name of the database column */
    private String name;
    
    /** The data type of the column (as it appears in database metadata) */
    private String type;
    
    /** Whether this column is part of the primary key */
    private boolean isPrimaryKey;
    
    /** Whether this column allows null values */
    private boolean isNullable;

    private String originalType;

    /**
     * Constructs a new ColumnInfo with all metadata properties.
     * 
     * @param name the name of the column
     * @param type the data type of the column
     * @param isPrimaryKey whether this column is part of the primary key
     * @param isNullable whether this column allows null values
     */
    public ColumnInfo(String name, String type, boolean isPrimaryKey, boolean isNullable) {
        this.name = name;
        this.type = type;
        this.isPrimaryKey = isPrimaryKey;
        this.isNullable = isNullable;
    }

    public ColumnInfo() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isPrimaryKey() {
        return isPrimaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        isPrimaryKey = primaryKey;
    }

    public boolean isNullable() {
        return isNullable;
    }

    public void setNullable(boolean nullable) {
        isNullable = nullable;
    }

    public String getOriginalType() {
        return originalType;
    }

    public void setOriginalType(String originalType) {
        this.originalType = originalType;
    }

    public Boolean hasOriginalType() {
        return this.originalType != null;
    }

}