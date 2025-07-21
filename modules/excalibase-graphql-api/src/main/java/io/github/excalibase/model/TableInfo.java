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
 * Represents database table metadata used for GraphQL schema generation.
 */
public class TableInfo {
    
    private String name;
    private List<ColumnInfo> columns = new ArrayList<>();
    private List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
    private boolean isView = false;

    public TableInfo(String name, List<ColumnInfo> columns, List<ForeignKeyInfo> foreignKeys) {
        this.name = name;
        this.columns = columns;
        this.foreignKeys = foreignKeys;
        this.isView = false;
    }

    public TableInfo(String name, List<ColumnInfo> columns, List<ForeignKeyInfo> foreignKeys, boolean isView) {
        this.name = name;
        this.columns = columns;
        this.foreignKeys = foreignKeys;
        this.isView = isView;
    }

    public TableInfo() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
    }

    public List<ForeignKeyInfo> getForeignKeys() {
        return foreignKeys;
    }

    public void setForeignKeys(List<ForeignKeyInfo> foreignKeys) {
        this.foreignKeys = foreignKeys;
    }

    public boolean isView() {
        return isView;
    }

    public void setView(boolean view) {
        isView = view;
    }
}