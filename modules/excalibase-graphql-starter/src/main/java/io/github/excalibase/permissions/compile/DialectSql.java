package io.github.excalibase.permissions.compile;

import io.github.excalibase.schema.SchemaInfo;

import java.util.List;
import java.util.Optional;

/** What a {@link PermissionDialect} differs in; the compiler asks it and nothing else about the database. */
sealed interface DialectSql permits PostgresSql, MysqlSql {

    String quote(String identifier);

    /** A plain identifier, or one this database's engine dialect already quoted. */
    boolean safeAlias(String alias);

    /** The type of a reflected column whose reported type is {@code sqlType}. */
    ColumnType columnType(SchemaInfo schema, String tableKey, String column, String sqlType);

    /** A JDBC, change-feed or permission value as the type's Java value; IllegalArgumentException if it is not one. */
    Object canonical(ColumnType type, Object value);

    /** A canonical value bound where it is compared with the column. */
    Object compareValue(ColumnType type, Object canonical);

    /** A canonical value bound where it is written into the column. */
    Object writeValue(ColumnType type, Object canonical);

    /** Where a compared value goes in SQL. */
    String placeholder(ColumnType type, String param);

    /** A value typed as the column for an image row, so it compares as the column would; empty if it cannot be. */
    Optional<String> imagePlaceholder(ColumnType type, String param);

    /** The column as comparisons read it; {@code image} when {@code reference} is an image row's value. */
    String compared(ColumnType type, String reference, boolean image);

    /** The FROM clause of an image row: one row of {@code placeholders} named {@code names}. */
    String imageRow(String alias, List<String> placeholders, List<String> names);

    boolean equatableInMemory(ColumnType type);

    /** Whether a float column can hold NaN and infinities, so comparing with one is left to the database. */
    boolean storesNonFiniteFloats();

    /** Whether an UPDATE or DELETE must read its own table through a derived table (MySQL error 1093). */
    boolean readsWrittenTableThroughDerivedTable();

    boolean tracksFunctions();
}
