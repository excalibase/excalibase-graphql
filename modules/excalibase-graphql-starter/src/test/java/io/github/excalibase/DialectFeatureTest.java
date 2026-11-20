package io.github.excalibase;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DialectFeatureTest {

    /** A dialect that declares nothing: what a new database starts from. */
    private static final SqlDialect BARE = new SqlDialect() {
        public String buildObject(List<String> pairs) { return ""; }
        public String aggregateArray(String expr) { return ""; }
        public String coalesceArray(String expr) { return ""; }
        public String quoteIdentifier(String id) { return id; }
        public String qualifiedTable(String schema, String table) { return table; }
        public String encodeCursor(String expr) { return expr; }
        public String ilike(String colRef, String paramRef) { return ""; }
        public String orderByNulls(String colRef, String direction, String nullsPosition) { return ""; }
        public String suffixCast(String dataType) { return ""; }
        public String onConflict(List<String> conflictCols, List<String> updateSetClauses) { return ""; }
        public boolean supportsReturning() { return false; }
        public String cteName(String blockAlias, String suffix) { return ""; }
        public String randAlias() { return ""; }
        public String distinctOn(List<String> columns, String alias) { return ""; }
    };

    @Test
    void aDialectThatDeclaresNothingRunsNoOptionalFeature() {
        for (DialectFeature feature : DialectFeature.values()) {
            assertThat(BARE.supports(feature)).as(feature.name()).isFalse();
        }
        assertThat(BARE.features()).isEmpty();
    }

    @Test
    void aDialectRunsWhatItDeclares() {
        SqlDialect declared = new SqlDialect() {
            public String buildObject(List<String> pairs) { return ""; }
            public String aggregateArray(String expr) { return ""; }
            public String coalesceArray(String expr) { return ""; }
            public String quoteIdentifier(String id) { return id; }
            public String qualifiedTable(String schema, String table) { return table; }
            public String encodeCursor(String expr) { return expr; }
            public String ilike(String colRef, String paramRef) { return ""; }
            public String orderByNulls(String colRef, String direction, String nullsPosition) { return ""; }
            public String suffixCast(String dataType) { return ""; }
            public String onConflict(List<String> conflictCols, List<String> updateSetClauses) { return ""; }
            public boolean supportsReturning() { return false; }
            public String cteName(String blockAlias, String suffix) { return ""; }
            public String randAlias() { return ""; }
            public String distinctOn(List<String> columns, String alias) { return ""; }
            @Override
            public Set<DialectFeature> features() { return EnumSet.of(DialectFeature.AGGREGATES); }
        };
        assertThat(declared.supports(DialectFeature.AGGREGATES)).isTrue();
        assertThat(declared.supports(DialectFeature.UPSERT)).isFalse();
    }
}
