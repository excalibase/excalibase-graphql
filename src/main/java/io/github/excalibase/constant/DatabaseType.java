package io.github.excalibase.constant;

public enum DatabaseType {
    POSTGRES("Postgres"),
    MYSQL("MySQL"),
    ORACLE("Oracle"),
    SQL_SERVER("SQL Server"),
    DYNAMODB("DynamoDB");

    private String name;

    private DatabaseType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
