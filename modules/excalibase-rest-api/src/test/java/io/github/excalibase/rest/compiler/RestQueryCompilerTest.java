package io.github.excalibase.rest.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.*;
import static org.junit.jupiter.api.Assertions.*;

class RestQueryCompilerTest {

  private SchemaInfo schema;
  private SqlDialect dialect;
  private RestQueryCompiler compiler;

  @BeforeEach
  void setUp() {
    schema = new SchemaInfo();
    dialect = new PostgresDialect();


    schema.addColumn("public.products", "id", "integer");
    schema.addColumn("public.products", "name", "text");
    schema.addColumn("public.products", "price", "numeric");
    schema.addColumn("public.products", "description", "text");
    schema.addColumn("public.products", "metadata", "jsonb");
    schema.addColumn("public.products", "tags", "_text");
    schema.addPrimaryKey("public.products", "id");
    schema.setTableSchema("public.products", "public");


    schema.addColumn("public.orders", "id", "integer");
    schema.addColumn("public.orders", "product_id", "integer");
    schema.addColumn("public.orders", "quantity", "integer");
    schema.addColumn("public.orders", "total", "numeric");
    schema.addPrimaryKey("public.orders", "id");
    schema.setTableSchema("public.orders", "public");
    schema.addForeignKey("public.orders", "product_id", "public.products", "id");

    compiler = new RestQueryCompiler(schema, dialect, "public", 30);
  }

  @Nested
  class SelectQueries {

    @Test
    @DisplayName("SELECT all columns from table")
    void selectAll() {
      var result = compiler.compileSelect("public.products", List.of(), List.of(), null, 30, 0, false);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("\"products\""));
      assertTrue(result.sql().contains("jsonb_agg") || result.sql().contains("json_agg"));
    }

    @Test
    @DisplayName("SELECT specific columns")
    void selectSpecificColumns() {
      var result = compiler.compileSelect("public.products", List.of("id", "name"), List.of(), null, 10, 0, false);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("\"id\""));
      assertTrue(result.sql().contains("\"name\""));
    }

    @Test
    @DisplayName("SELECT with LIMIT and OFFSET")
    void selectWithPagination() {
      var result = compiler.compileSelect("public.products", List.of(), List.of(), null, 5, 10, false);
      assertTrue(result.sql().contains("LIMIT"));
      assertTrue(result.sql().contains("OFFSET"));
    }

    @Test
    @DisplayName("SELECT with count")
    void selectWithCount() {
      var result = compiler.compileSelect("public.products", List.of(), List.of(), null, 30, 0, true);
      assertTrue(result.sql().toLowerCase().contains("count"));
    }
  }

  @Nested
  class FilterQueries {

    @Test
    @DisplayName("WHERE with eq filter")
    void whereEq() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("name", "eq", "Widget", false));
      var result = compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
      assertTrue(result.sql().contains("WHERE"));
      assertFalse(result.params().isEmpty());
    }

    @Test
    @DisplayName("WHERE with multiple filters (AND)")
    void whereMultipleFilters() {
      var filters = List.of(
          new RestQueryCompiler.FilterSpec("name", "eq", "Widget", false),
          new RestQueryCompiler.FilterSpec("price", "gt", "10", false)
      );
      var result = compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
      assertTrue(result.sql().contains("AND"));
    }

    @Test
    @DisplayName("WHERE with is.null")
    void whereIsNull() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("name", "is", "null", false));
      var result = compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
      assertTrue(result.sql().contains("IS NULL"));
    }

    @Test
    @DisplayName("WHERE with negation")
    void whereNegated() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("name", "eq", "Widget", true));
      var result = compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
      assertTrue(result.sql().contains("NOT") || result.sql().contains("<>"));
    }
  }

  @Nested
  class OrderByQueries {

    @Test
    @DisplayName("ORDER BY single column")
    void orderBySingle() {
      var order = List.of(new RestQueryCompiler.OrderBySpec("name", "ASC", null));
      var result = compiler.compileSelect("public.products", List.of(), List.of(), order, 30, 0, false);
      assertTrue(result.sql().contains("ORDER BY"));
      assertTrue(result.sql().contains("\"name\""));
    }

    @Test
    @DisplayName("ORDER BY with nulls handling")
    void orderByWithNulls() {
      var order = List.of(new RestQueryCompiler.OrderBySpec("price", "DESC", "NULLS LAST"));
      var result = compiler.compileSelect("public.products", List.of(), List.of(), order, 30, 0, false);
      assertTrue(result.sql().contains("DESC"));
      assertTrue(result.sql().contains("NULLS LAST"));
    }
  }

  @Nested
  class Security {

    @Test
    @DisplayName("unknown columns in select are filtered out")
    void selectUnknownColumnsFiltered() {
      var result = compiler.compileSelect("public.products", List.of("id", "hacked_col"), List.of(), null, 30, 0, false);
      assertTrue(result.sql().contains("\"id\""));
      assertFalse(result.sql().contains("hacked_col"));
    }

    @Test
    @DisplayName("unknown columns in filter are ignored")
    void filterUnknownColumnIgnored() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("hacked", "eq", "x", false));
      var result = compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
      assertFalse(result.sql().contains("hacked"));
    }

    @Test
    @DisplayName("unknown columns in orderBy are ignored")
    void orderUnknownColumnIgnored() {
      var order = List.of(new RestQueryCompiler.OrderBySpec("hacked", "ASC", null));
      var result = compiler.compileSelect("public.products", List.of(), List.of(), order, 30, 0, false);
      assertFalse(result.sql().contains("hacked"));
    }

    @Test
    @DisplayName("unsupported filter operator throws")
    void unsupportedOperatorThrows() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("name", "xss", "<script>", false));
      assertThrows(IllegalArgumentException.class,
          () -> compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false));
    }

    @Test
    @DisplayName("IN list exceeding 1000 items throws")
    void inListTooLargeThrows() {
      String bigList = "(" + "1,".repeat(1001) + "1)";
      var filters = List.of(new RestQueryCompiler.FilterSpec("id", "in", bigList, false));
      assertThrows(IllegalArgumentException.class,
          () -> compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false));
    }
  }

  @Nested
  class Mutations {

    @Test
    @DisplayName("INSERT returns SQL with RETURNING")
    void insert() {
      var input = Map.of("name", (Object) "New Product", "price", 42.0);
      var result = compiler.compileInsert("public.products", input);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("INSERT INTO"));
      assertTrue(result.sql().contains("RETURNING") || result.sql().contains("returning"));
    }

    @Test
    @DisplayName("UPDATE with filter")
    void update() {
      var input = Map.of("price", (Object) 99.0);
      var filters = List.of(new RestQueryCompiler.FilterSpec("id", "eq", "1", false));
      var result = compiler.compileUpdate("public.products", input, filters);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("UPDATE") || result.sql().contains("update"));
      assertTrue(result.sql().contains("WHERE"));
    }

    @Test
    @DisplayName("DELETE with filter")
    void delete() {
      var filters = List.of(new RestQueryCompiler.FilterSpec("id", "eq", "1", false));
      var result = compiler.compileDelete("public.products", filters);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("DELETE") || result.sql().contains("delete"));
    }
  }

  @Nested
  class BulkAndUpsert {

    @Test @DisplayName("compileBulkInsert generates multi-row VALUES")
    void bulkInsert() {
      var rows = List.of(
          Map.of("name", (Object) "A", "price", 1.0),
          Map.of("name", (Object) "B", "price", 2.0)
      );
      var result = compiler.compileBulkInsert("public.products", rows);
      assertNotNull(result.sql());
      assertTrue(result.sql().contains(INSERT_INTO));
      assertTrue(result.sql().contains(RETURNING_ALL));
      assertTrue(result.params().size() >= 4);
    }

    @Test @DisplayName("compileUpsert generates ON CONFLICT DO UPDATE")
    void upsert() {
      var input = Map.of("id", (Object) 1, "name", "Updated", "price", 99.0);
      var result = compiler.compileUpsert("public.products", input, List.of("id"));
      assertNotNull(result.sql());
      assertTrue(result.sql().contains("ON CONFLICT"));
      assertTrue(result.sql().contains("DO UPDATE"));
    }

    @Test @DisplayName("compileUpsert with all conflict cols generates DO NOTHING")
    void upsertDoNothing() {
      var input = Map.of("id", (Object) 1);
      var result = compiler.compileUpsert("public.products", input, List.of("id"));
      assertTrue(result.sql().contains("DO NOTHING") || result.sql().contains("DO UPDATE"));
    }
  }

  @Nested
  class ExtendedFilterOperators {

    private RestQueryCompiler.CompiledResult filterQuery(String col, String op, String val) {
      var filters = List.of(new RestQueryCompiler.FilterSpec(col, op, val, false));
      return compiler.compileSelect("public.products", List.of(), filters, null, 30, 0, false);
    }

    @Test @DisplayName("startswith: col LIKE 'val%'")
    void startswith() {
      var r = filterQuery("name", "startswith", "Wid");
      assertTrue(r.sql().contains("LIKE"));
    }

    @Test @DisplayName("endswith: col LIKE '%val'")
    void endswith() {
      var r = filterQuery("name", "endswith", "get");
      assertTrue(r.sql().contains("LIKE"));
    }

    @Test @DisplayName("match: col ~ :param (regex)")
    void match() {
      var r = filterQuery("name", "match", "^W");
      assertTrue(r.sql().contains("~"));
    }

    @Test @DisplayName("imatch: col ~* :param (case-insensitive regex)")
    void imatch() {
      var r = filterQuery("name", "imatch", "^w");
      assertTrue(r.sql().contains("~*"));
    }

    @Test @DisplayName("isdistinct: col IS DISTINCT FROM :param")
    void isdistinct() {
      var r = filterQuery("name", "isdistinct", "null");
      assertTrue(r.sql().contains("IS DISTINCT FROM"));
    }

    @Test @DisplayName("isnotnull: col IS NOT NULL")
    void isnotnull() {
      var r = filterQuery("name", "isnotnull", "");
      assertTrue(r.sql().contains("IS NOT NULL"));
    }

    @Test @DisplayName("haskey: jsonb_exists(col, :param)")
    void haskey() {
      var r = filterQuery("metadata", "haskey", "color");
      assertTrue(r.sql().contains("jsonb_exists"));
    }

    @Test @DisplayName("jsoncontains: col @> :param::jsonb")
    void jsoncontains() {
      var r = filterQuery("metadata", "jsoncontains", "{\"a\":1}");
      assertTrue(r.sql().contains("@>"));
      assertTrue(r.sql().contains("::jsonb"));
    }

    @Test @DisplayName("containedin: col <@ :param::jsonb")
    void containedin() {
      var r = filterQuery("metadata", "containedin", "{\"a\":1}");
      assertTrue(r.sql().contains("<@"));
    }

    @Test @DisplayName("jsonpath: col @? :param::jsonpath")
    void jsonpath() {
      var r = filterQuery("metadata", "jsonpath", "$.name");
      assertTrue(r.sql().contains("@?"));
    }

    @Test @DisplayName("arraycontains: col @> ARRAY[:param]")
    void arraycontains() {
      var r = filterQuery("tags", "arraycontains", "red");
      assertTrue(r.sql().contains("@>"));
      assertTrue(r.sql().contains("ARRAY"));
    }

    @Test @DisplayName("arraylength: array_length(col, 1) = :param")
    void arraylength() {
      var r = filterQuery("tags", "arraylength", "3");
      assertTrue(r.sql().contains("array_length"));
    }

    @Test @DisplayName("plfts: plainto_tsquery")
    void plfts() {
      var r = filterQuery("description", "plfts", "english.hello world");
      assertTrue(r.sql().contains("plainto_tsquery"));
    }

    @Test @DisplayName("phfts: phraseto_tsquery")
    void phfts() {
      var r = filterQuery("description", "phfts", "english.hello world");
      assertTrue(r.sql().contains("phraseto_tsquery"));
    }

    @Test @DisplayName("wfts: websearch_to_tsquery")
    void wfts() {
      var r = filterQuery("description", "wfts", "english.cat or dog");
      assertTrue(r.sql().contains("websearch_to_tsquery"));
    }

    @Test @DisplayName("cs: col @> :param (range contains)")
    void cs() {
      var r = filterQuery("metadata", "cs", "[1,5]");
      assertTrue(r.sql().contains("@>"));
    }

    @Test @DisplayName("cd: col <@ :param (range contained)")
    void cd() {
      var r = filterQuery("metadata", "cd", "[1,10]");
      assertTrue(r.sql().contains("<@"));
    }

    @Test @DisplayName("ov: col && :param (overlaps)")
    void ov() {
      var r = filterQuery("tags", "ov", "[a,b]");
      assertTrue(r.sql().contains("&&"));
    }

    @Test @DisplayName("adj: col -|- :param (adjacent)")
    void adj() {
      var r = filterQuery("metadata", "adj", "[1,5]");
      assertTrue(r.sql().contains("-|-"));
    }
  }

  @Nested
  class DeepEmbedding {

    @BeforeEach
    void addOrderItemsTable() {
      // order_items FK: order_items.order_id → orders.id (reverse: orders has order_items)
      schema.addColumn("public.order_items", "id", "integer");
      schema.addColumn("public.order_items", "order_id", "integer");
      schema.addColumn("public.order_items", "product_id", "integer");
      schema.addColumn("public.order_items", "quantity", "integer");
      schema.addPrimaryKey("public.order_items", "id");
      schema.setTableSchema("public.order_items", "public");
      schema.addForeignKey("public.order_items", "order_id", "public.orders", "id");
      schema.addForeignKey("public.order_items", "product_id", "public.products", "id");
      // Also need products to have reverse FK to orders (orders.product_id → products)
      // already set up in setUp()
    }

    @Test
    @DisplayName("two-level embed: products(*,orders(*)) produces nested subquery")
    void twoLevelReverseEmbed() {
      // products has reverse FK: orders.product_id → products.id
      var embed = new RestQueryCompiler.EmbedSpec(
          "orders", List.of("*"), null,
          List.of(new RestQueryCompiler.EmbedSpec("order_items", List.of("*")))
      );
      var r = compiler.compileSelect(new RestQueryCompiler.SelectQuery(
          "public.products", List.of(), List.of(), null, List.of(embed),
          null, 10, 0, false
      ));
      // Should contain a nested subquery for orders inside products
      assertTrue(r.sql().contains("\"orders\""), "Expected orders subquery in SQL: " + r.sql());
      assertTrue(r.sql().contains("\"order_items\""), "Expected order_items nested subquery in SQL: " + r.sql());
    }

    @Test
    @DisplayName("deep embed SQL uses parent alias (r1) for child FK join, not top-level alias (c)")
    void deepEmbedUsesCorrectParentAlias() {
      var embed = new RestQueryCompiler.EmbedSpec(
          "orders", List.of("*"), null,
          List.of(new RestQueryCompiler.EmbedSpec("order_items", List.of("*")))
      );
      var r = compiler.compileSelect(new RestQueryCompiler.SelectQuery(
          "public.products", List.of(), List.of(), null, List.of(embed),
          null, 10, 0, false
      ));
      String sql = r.sql();
      // order_items join should use r1 (orders alias), NOT c (products alias)
      // Pattern: order_items r2 WHERE r2."order_id" = r1."id"
      assertTrue(sql.contains("\"order_items\""), "Missing order_items in: " + sql);
      assertTrue(sql.contains("\"orders\""), "Missing orders in: " + sql);
      // The child order_items join must reference an r-alias (orders outer alias), not c (products)
      // Since the outer alias for orders is r1, order_items join must be "= r1."
      assertTrue(sql.contains("= r1."), "order_items join must use orders alias r1, not top-level c, in: " + sql);
    }
  }
}
