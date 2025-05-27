package io.github.excalibase.model;

import java.util.ArrayList;
import java.util.List;

public class TableInfo {
    private String name;
    private List<ColumnInfo> columns = new ArrayList<>();
    private List<ForeignKeyInfo> foreignKeys = new ArrayList<>();

    public TableInfo(String name, List<ColumnInfo> columns, List<ForeignKeyInfo> foreignKeys) {
        this.name = name;
        this.columns = columns;
        this.foreignKeys = foreignKeys;
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
}