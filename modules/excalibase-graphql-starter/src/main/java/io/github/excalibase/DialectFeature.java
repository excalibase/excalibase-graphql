package io.github.excalibase;

/**
 * A feature beyond plain CRUD that a database may or may not run. A dialect lists the ones it runs in
 * {@link SqlDialect#features()}; the schema offers only those, and a request that reaches an undeclared one
 * is refused with a message naming the database, never compiled into SQL that drops part of the request.
 */
public enum DialectFeature {
    /** {@code <table>Aggregate} queries and {@code totalCount}. */
    AGGREGATES,
    /** The {@code onConflict} argument of {@code create}, and REST's {@code on_conflict}. */
    UPSERT,
    /** The {@code distinct_on} argument of list queries. */
    DISTINCT_ON,
    /** A change stream behind the {@code <table>Changes} subscriptions. */
    SUBSCRIPTIONS
}
