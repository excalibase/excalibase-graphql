package io.github.excalibase.permissions.compile;

/**
 * The SQL a permission compiles to, per database: quoting, the column type table, how values bind and
 * compare, and the realtime row probe. Permissions are one model with one enforcement point (ADR 0036);
 * only the SQL they compile to differs (ADR 0041).
 */
public enum PermissionDialect {
    POSTGRES(new PostgresSql()),
    MYSQL(new MysqlSql());

    private final DialectSql sql;

    PermissionDialect(DialectSql sql) {
        this.sql = sql;
    }

    DialectSql sql() {
        return sql;
    }

    /** Postgres functions can return table rows; MySQL functions return scalars, so none can be tracked. */
    public boolean tracksFunctions() {
        return sql.tracksFunctions();
    }

    /** {@code identifier} quoted for this database. */
    public String quote(String identifier) {
        return sql.quote(identifier);
    }
}
