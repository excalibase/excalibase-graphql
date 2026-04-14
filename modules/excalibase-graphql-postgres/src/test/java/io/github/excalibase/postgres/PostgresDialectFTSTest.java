package io.github.excalibase.postgres;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PostgresDialect#fullTextSearchSql(String, String, boolean)}.
 *
 * <p>The test covers both code paths in isolation; an end-to-end test that
 * actually executes the SQL against a tsvector column lives in the integration
 * suite for Phase 5's wiring (FilterBuilder + introspected pg_search detection).
 */
class PostgresDialectFTSTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void vanillaTsVectorPath_emitsPlainToTsquery() {
        Optional<String> sql = dialect.fullTextSearchSql("t.body", ":p_body_search", false);
        assertTrue(sql.isPresent());
        assertEquals("t.body @@ plainto_tsquery(:p_body_search)", sql.get());
    }

    @Test
    void bm25Path_emitsParadeOperator() {
        Optional<String> sql = dialect.fullTextSearchSql("t.body", ":p_body_search", true);
        assertTrue(sql.isPresent());
        assertEquals("t.body @@@ :p_body_search", sql.get());
    }

    @Test
    void quotedColumnReferenceIsPreserved() {
        // FilterBuilder always passes alias.quoteIdentifier(col), so the dialect
        // must echo the quoted form into the resulting SQL untouched.
        Optional<String> sql = dialect.fullTextSearchSql("t.\"full-text\"", ":p_search", false);
        assertTrue(sql.isPresent());
        assertEquals("t.\"full-text\" @@ plainto_tsquery(:p_search)", sql.get());
    }
}
