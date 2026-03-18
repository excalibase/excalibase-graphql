package io.github.excalibase.postgres.util;

import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresSqlBuilderTest {

    private PostgresSqlBuilder builder;

    @BeforeEach
    void setUp() {
        IDatabaseSchemaReflector reflector = mock(IDatabaseSchemaReflector.class);
        when(reflector.getCustomEnumTypes()).thenReturn(List.of());
        when(reflector.getCustomCompositeTypes()).thenReturn(List.of());
        builder = new PostgresSqlBuilder(new PostgresTypeConverter(reflector));
    }

    // ── OR inside where ──────────────────────────────────────────────────────

    @Test
    void buildWhereConditions_orInsideWhere_generatesSingleOrClause() {
        Map<String, Object> where = Map.of(
            "or", List.of(
                Map.of("status", Map.of("eq", "PENDING")),
                Map.of("status", Map.of("eq", "PROCESSING"))
            )
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", where), params, Map.of("status", "character varying")
        );

        assertThat(conditions).hasSize(1);
        assertThat(conditions.getFirst()).startsWith("(");
        assertThat(conditions.getFirst()).contains(" OR ");
        assertThat(conditions.getFirst()).contains("\"status\"");
    }

    @Test
    void buildWhereConditions_orInsideWhere_bothBranchesPresent() {
        Map<String, Object> where = Map.of(
            "or", List.of(
                Map.of("status", Map.of("eq", "PENDING")),
                Map.of("status", Map.of("eq", "PROCESSING"))
            )
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", where), params, Map.of("status", "character varying")
        );

        // Both PENDING and PROCESSING param values should be bound
        assertThat(params.getValues()).hasSize(2);
    }

    @Test
    void buildWhereConditions_orInsideWhere_withMultipleColumns() {
        Map<String, Object> where = Map.of(
            "or", List.of(
                Map.of("status", Map.of("eq", "PENDING")),
                Map.of("total_amount", Map.of("gt", 100))
            )
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", where), params, Map.of("status", "character varying", "total_amount", "numeric")
        );

        assertThat(conditions).hasSize(1);
        String clause = conditions.getFirst();
        assertThat(clause).contains(" OR ");
        assertThat(clause).contains("\"status\"");
        assertThat(clause).contains("\"total_amount\"");
    }

    @Test
    void buildWhereConditions_orInsideWhere_combinedWithOtherFilters() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        // where: { customer_id: { eq: 1 }, or: [{ status: { eq: PENDING } }, { status: { eq: PROCESSING } }] }
        Map<String, Object> where = new java.util.HashMap<>();
        where.put("customer_id", Map.of("eq", 1));
        where.put("or", List.of(
            Map.of("status", Map.of("eq", "PENDING")),
            Map.of("status", Map.of("eq", "PROCESSING"))
        ));

        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", where), params,
            Map.of("customer_id", "integer", "status", "character varying")
        );

        // One condition for customer_id eq, one for the OR clause
        assertThat(conditions).hasSize(2);
        boolean hasOr = conditions.stream().anyMatch(c -> c.contains(" OR "));
        assertThat(hasOr).isTrue();
    }

    @Test
    void buildWhereConditions_emptyOrList_producesNoCondition() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", Map.of("or", List.of())),
            params,
            Map.of("status", "character varying")
        );

        assertThat(conditions).isEmpty();
    }

    @Test
    void buildWhereConditions_topLevelOr_generatesOrClause() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        List<String> conditions = builder.buildWhereConditions(
            Map.of("or", List.of(
                Map.of("status", Map.of("eq", "DELIVERED")),
                Map.of("status", Map.of("eq", "SHIPPED"))
            )),
            params,
            Map.of("status", "character varying")
        );

        assertThat(conditions).hasSize(1);
        assertThat(conditions.getFirst()).contains(" OR ");
    }

    // ── Basic where (regression guard) ──────────────────────────────────────

    @Test
    void buildWhereConditions_simpleEq_generatesEqualityCondition() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        List<String> conditions = builder.buildWhereConditions(
            Map.of("where", Map.of("customer_id", Map.of("eq", 1))),
            params,
            Map.of("customer_id", "integer")
        );

        assertThat(conditions).hasSize(1);
        assertThat(conditions.getFirst()).contains("\"customer_id\"");
        assertThat(params.getValues()).containsKey("where_customer_id_eq");
    }

    @Test
    void buildWhereConditions_noArguments_returnsEmptyList() {
        List<String> conditions = builder.buildWhereConditions(
            Map.of(), new MapSqlParameterSource(), Map.of()
        );

        assertThat(conditions).isEmpty();
    }

    // ── buildColumnListWithAliases ────────────────────────────────────────────

    @Test
    void buildColumnListWithAliases_normalColumn_quotesWithoutAlias() {
        // "actor_id" has no special chars → alias == name → no AS clause
        ColumnInfo col = new ColumnInfo("actor_id", "integer", true, false);
        col.setAliasName("actor_id");

        String sql = builder.buildColumnListWithAliases(List.of(col));

        assertThat(sql).isEqualTo("\"actor_id\"");
    }

    @Test
    void buildColumnListWithAliases_spaceInColumnName_emitsAsAlias() {
        // "zip code" (space) → aliasName "zip_code" → SELECT "zip code" AS zip_code
        ColumnInfo col = new ColumnInfo("zip code", "character varying", false, true);
        col.setAliasName("zip_code");

        String sql = builder.buildColumnListWithAliases(List.of(col));

        assertThat(sql).isEqualTo("\"zip code\" AS zip_code");
    }

    @Test
    void buildColumnListWithAliases_mixedColumns_correctlyHandlesBoth() {
        ColumnInfo normal = new ColumnInfo("phone", "character varying", false, true);
        normal.setAliasName("phone");

        ColumnInfo spaced = new ColumnInfo("zip code", "character varying", false, true);
        spaced.setAliasName("zip_code");

        String sql = builder.buildColumnListWithAliases(List.of(normal, spaced));

        assertThat(sql).isEqualTo("\"phone\", \"zip code\" AS zip_code");
    }

    // ── ColumnInfo.aliasName ──────────────────────────────────────────────────

    @Test
    void columnInfo_aliasName_defaultsToName() {
        ColumnInfo col = new ColumnInfo("actor_id", "integer", true, false);
        col.setAliasName("actor_id");

        assertThat(col.getAliasName()).isEqualTo("actor_id");
        assertThat(col.hasAlias()).isFalse();
    }

    @Test
    void columnInfo_aliasName_detectsAlias() {
        ColumnInfo col = new ColumnInfo("zip code", "character varying", false, true);
        col.setAliasName("zip_code");

        assertThat(col.getAliasName()).isEqualTo("zip_code");
        assertThat(col.hasAlias()).isTrue();
    }

    // ── PostgresSchemaHelper.getAvailableColumns returns alias names ──────────

    @Test
    void schemaHelper_getAvailableColumns_returnsAliasNames() {
        // Simulate staff_list view with "zip code" column that has alias "zip_code"
        ColumnInfo id = new ColumnInfo("id", "integer", true, false);
        id.setAliasName("id");

        ColumnInfo zipCode = new ColumnInfo("zip code", "character varying", false, true);
        zipCode.setAliasName("zip_code");

        TableInfo tableInfo = new TableInfo();
        tableInfo.setName("staff_list");
        tableInfo.setColumns(List.of(id, zipCode));

        IDatabaseSchemaReflector reflector = mock(IDatabaseSchemaReflector.class);
        when(reflector.reflectSchema()).thenReturn(Map.of("staff_list", tableInfo));

        PostgresSchemaHelper helper = new PostgresSchemaHelper(reflector);
        List<String> cols = helper.getAvailableColumns("staff_list");

        // Must return alias names ("zip_code"), not raw DB names ("zip code")
        assertThat(cols).containsExactly("id", "zip_code");
        assertThat(cols).doesNotContain("zip code");
    }
}
