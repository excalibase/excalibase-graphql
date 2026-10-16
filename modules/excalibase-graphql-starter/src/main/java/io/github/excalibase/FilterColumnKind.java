package io.github.excalibase;

/** The column types filters are offered for; each gets its own operator set from {@link FilterCapabilities}. */
public enum FilterColumnKind {
    STRING,
    /** A text column a dialect can search by full text in place, e.g. MySQL with a FULLTEXT index on it alone. */
    FULL_TEXT_STRING,
    INT,
    FLOAT,
    BOOLEAN,
    DATE_TIME,
    JSON,
    TSVECTOR,
    ENUM
}
