package io.github.excalibase.mysql.constant;

/**
 * SQL constants for MySQL schema introspection queries.
 */
public class MysqlSqlConstant {
    private MysqlSqlConstant() {}

    public static final String GET_TABLE_NAMES =
            "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') " +
            "ORDER BY TABLE_NAME";

    public static final String GET_COLUMNS =
            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_TYPE " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION";

    public static final String GET_PRIMARY_KEYS =
            "SELECT TABLE_NAME, COLUMN_NAME " +
            "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
            "WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = 'PRIMARY' " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION";

    public static final String GET_FOREIGN_KEYS =
            "SELECT KCU.TABLE_NAME, KCU.COLUMN_NAME, " +
            "       KCU.REFERENCED_TABLE_NAME, KCU.REFERENCED_COLUMN_NAME " +
            "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU " +
            "WHERE KCU.TABLE_SCHEMA = ? AND KCU.REFERENCED_TABLE_NAME IS NOT NULL " +
            "ORDER BY KCU.TABLE_NAME";
}
