package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.header;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class RestControllerIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-rest-test.sql");

  @Override
  public Map<String, String> getProperties() {
      Map<String, String> properties = EngineProperties.datasource(postgres);
      properties.put("app.project-id", "test-proj");
      properties.put("app.security.jwt-enabled", "false");
      properties.put("app.security.insecure-dev-mode", "true");
      properties.put("app.database-type", "postgres");
      properties.put("app.max-rows", "30");
      return properties;
  }


  private static final String BASE = "/test-proj/api/v1";

  // ─── GET (list) ────────────────────────────────────────────────────────────

  @Nested
  @Order(1)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class ReadOperations {

    @Test @Order(1)
    @DisplayName("GET returns all records")
    void getAll() throws Exception {
      http.get(BASE + "/products")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(5)))
          .expect(jsonPath("$.data[0].name").exists());
    }

    @Test @Order(2)
    @DisplayName("GET with select: ?select=id,name returns only those columns")
    void selectColumns() throws Exception {
      http.get(BASE + "/products?select=id,name")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].id").exists())
          .expect(jsonPath("$.data[0].name").exists())
          .expect(jsonPath("$.data[0].price").doesNotExist());
    }

    @Test @Order(3)
    @DisplayName("GET with limit and offset")
    void pagination() throws Exception {
      http.get(BASE + "/products?limit=2&offset=1")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)));
    }

    @Test @Order(4)
    @DisplayName("GET with Prefer: count=exact returns Content-Range")
    void countExact() throws Exception {
      http.get(BASE + "/products", "Prefer", "count=exact")
          .expect(status().isOk())
          .expect(header().string("Content-Range", containsString("/5")))
          .expect(jsonPath("$.pagination.total", is(5)));
    }

    @Test @Order(5)
    @DisplayName("GET nonexistent table returns 404")
    void unknownTable() throws Exception {
      http.get(BASE + "/nonexistent")
          .expect(status().isNotFound());
    }

    @Test @Order(6)
    @DisplayName("GET view returns data")
    void queryView() throws Exception {
      http.get(BASE + "/expensive_products")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2))); // Gadget(19.99), Gizmo(29.99)
    }
  }

  // ─── Filtering ─────────────────────────────────────────────────────────────

  @Nested
  @Order(2)
  class Filtering {

    @Test
    @DisplayName("eq: ?name=eq.Widget")
    void filterEq() throws Exception {
      http.get(BASE + "/products?name=eq.Widget")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].name", is("Widget")));
    }

    @Test
    @DisplayName("neq: ?name=neq.Widget returns non-Widget products")
    void filterNeq() throws Exception {
      http.get(BASE + "/products?name=neq.Widget")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("gt: ?price=gt.15 returns expensive products")
    void filterGt() throws Exception {
      http.get(BASE + "/products?price=gt.15")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2))); // Gadget(19.99), Gizmo(29.99)
    }

    @Test
    @DisplayName("gte: ?price=gte.19.99")
    void filterGte() throws Exception {
      http.get(BASE + "/products?price=gte.19.99")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("lt: ?price=lt.10")
    void filterLt() throws Exception {
      http.get(BASE + "/products?price=lt.10")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2))); // Widget(9.99), Thingamajig(4.99)
    }

    @Test
    @DisplayName("lte: ?price=lte.9.99")
    void filterLte() throws Exception {
      http.get(BASE + "/products?price=lte.9.99")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("is.null: ?category=is.null")
    void filterIsNull() throws Exception {
      // No null categories in our data, should return 0
      http.get(BASE + "/products?category=is.null")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("is.true: ?in_stock=is.true")
    void filterIsTrue() throws Exception {
      http.get(BASE + "/products?in_stock=is.true")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(4))); // All except Gizmo
    }

    @Test
    @DisplayName("is.false: ?in_stock=is.false")
    void filterIsFalse() throws Exception {
      http.get(BASE + "/products?in_stock=is.false")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].name", is("Gizmo")));
    }

    @Test
    @DisplayName("like: ?name=like.*adget")
    void filterLike() throws Exception {
      http.get(BASE + "/products?name=like.*adget")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].name", is("Gadget")));
    }

    @Test
    @DisplayName("not.eq: ?name=not.eq.Widget")
    void filterNegation() throws Exception {
      http.get(BASE + "/products?name=not.eq.Widget")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("multiple filters: ?category=eq.electronics&in_stock=is.true")
    void multipleFilters() throws Exception {
      http.get(BASE + "/products?category=eq.electronics&in_stock=is.true")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].name", is("Gadget")));
    }
  }

  // ─── Ordering ──────────────────────────────────────────────────────────────

  @Nested
  @Order(3)
  class Ordering {

    @Test
    @DisplayName("order=price.asc")
    void orderAsc() throws Exception {
      http.get(BASE + "/products?order=price.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Thingamajig")))
          .expect(jsonPath("$.data[4].name", is("Gizmo")));
    }

    @Test
    @DisplayName("order=price.desc")
    void orderDesc() throws Exception {
      http.get(BASE + "/products?order=price.desc")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Gizmo")))
          .expect(jsonPath("$.data[0].price", is("29.99")));
    }

    @Test
    @DisplayName("order=name.asc with limit")
    void orderWithLimit() throws Exception {
      http.get(BASE + "/products?order=name.asc&limit=2")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].name", is("Doohickey")));
    }
  }

  // ─── CREATE (POST) ─────────────────────────────────────────────────────────

  @Nested
  @Order(10)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class CreateOperations {

    @Test @Order(1)
    @DisplayName("POST creates record with Prefer: return=representation")
    void createWithReturn() throws Exception {
      http.post(BASE + "/products", "{\"name\":\"Created Item\",\"price\":55.00,\"category\":\"test\"}", "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isCreated())
          .expect(jsonPath("$.data.name", is("Created Item")))
          .expect(jsonPath("$.data.price", is("55.00")))
          .expect(jsonPath("$.data.id").exists());
    }

    @Test @Order(2)
    @DisplayName("POST creates record without return (201 empty body)")
    void createWithoutReturn() throws Exception {
      http.post(BASE + "/products", "{\"name\":\"Silent Create\",\"price\":1.00}", "Content-Type", "application/json")
          .expect(status().isCreated());
    }
  }

  // ─── UPDATE (PATCH) ────────────────────────────────────────────────────────

  @Nested
  @Order(20)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class UpdateOperations {

    @Test @Order(1)
    @DisplayName("PATCH updates matching rows")
    void patchWithFilter() throws Exception {
      http.send("PATCH", BASE + "/products?id=eq.1", "{\"price\":99.99}", "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].price", is("99.99")));
    }

    @Test @Order(2)
    @DisplayName("PATCH updates multiple columns")
    void patchMultipleColumns() throws Exception {
      http.send("PATCH", BASE + "/products?id=eq.2", "{\"name\":\"Updated Gadget\",\"price\":25.00}", "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Updated Gadget")))
          .expect(jsonPath("$.data[0].price", is("25.00")));
    }
  }

  // ─── DELETE ────────────────────────────────────────────────────────────────

  @Nested
  @Order(30)
  class DeleteOperations {

    @Test
    @DisplayName("DELETE with filter returns deleted rows")
    void deleteWithReturn() throws Exception {
      // Create a temp record first
      http.post(BASE + "/products", "{\"name\":\"To Delete\",\"price\":0.01}", "Content-Type", "application/json")
          .expect(status().isCreated());

      http.delete(BASE + "/products?name=eq.To Delete", "Prefer", "return=representation")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("To Delete")));
    }
  }

  // ─── Repeated filters on one column (EXC-729) ─────────────────────────────

  @Nested
  @Order(31)
  class RangeFilters {

    private static final String RANGE = "category=eq.rangetest&price=gte.150&price=lt.250";

    private void seed() throws Exception {
      for (int price : new int[] {100, 200, 300}) {
        http.post(BASE + "/products", "{\"name\":\"range-" + price + "\",\"price\":" + price
                + ",\"category\":\"rangetest\"}", "Content-Type", "application/json")
            .expect(status().isCreated());
      }
    }

    private void cleanUp() throws Exception {
      http.delete(BASE + "/products?category=eq.rangetest&price=gte.0").expect(status().isOk());
    }

    @Test
    @DisplayName("a range of two filters on one column is ANDed on read and on count")
    void rangeRead() throws Exception {
      seed();
      try {
        http.get(BASE + "/products?" + RANGE)
            .expect(status().isOk())
            .expect(jsonPath("$.data", hasSize(1)))
            .expect(jsonPath("$.data[0].name", is("range-200")));
        http.get(BASE + "/products?" + RANGE, "Prefer", "count=exact")
            .expect(status().isOk())
            .expect(jsonPath("$.pagination.total", is(1)));
      } finally {
        cleanUp();
      }
    }

    @Test
    @DisplayName("a range PATCH changes only the rows inside the range")
    void rangeUpdate() throws Exception {
      seed();
      try {
        http.send("PATCH", BASE + "/products?" + RANGE, "{\"in_stock\":false}", "Content-Type", "application/json",
                "Prefer", "return=representation")
            .expect(status().isOk())
            .expect(jsonPath("$.data", hasSize(1)))
            .expect(jsonPath("$.data[0].name", is("range-200")));
        http.get(BASE + "/products?category=eq.rangetest&in_stock=eq.false")
            .expect(jsonPath("$.data", hasSize(1)));
      } finally {
        cleanUp();
      }
    }

    @Test
    @DisplayName("a range DELETE removes only the rows inside the range")
    void rangeDelete() throws Exception {
      seed();
      try {
        http.delete(BASE + "/products?" + RANGE, "Prefer", "return=representation")
            .expect(status().isOk())
            .expect(jsonPath("$.data", hasSize(1)))
            .expect(jsonPath("$.data[0].name", is("range-200")));
        http.get(BASE + "/products?category=eq.rangetest")
            .expect(jsonPath("$.data", hasSize(2)));
      } finally {
        cleanUp();
      }
    }

    @Test
    @DisplayName("an empty in.() list matches no rows, and not.in.() matches all")
    void emptyInList() throws Exception {
      seed();
      try {
        http.get(BASE + "/products?category=eq.rangetest&id=in.()")
            .expect(status().isOk())
            .expect(jsonPath("$.data", hasSize(0)));
        http.get(BASE + "/products?category=eq.rangetest&id=not.in.()")
            .expect(status().isOk())
            .expect(jsonPath("$.data", hasSize(3)));
      } finally {
        cleanUp();
      }
    }
  }

  // ─── Orders table (FK relationship data) ───────────────────────────────────

  @Nested
  @Order(4)
  class OrdersTable {

    @Test
    @DisplayName("GET orders returns order data")
    void getOrders() throws Exception {
      http.get(BASE + "/orders")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("GET orders with filter: ?status=eq.completed")
    void filterByStatus() throws Exception {
      http.get(BASE + "/orders?status=eq.completed")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("GET orders with numeric filter: ?total=gt.50")
    void filterByTotal() throws Exception {
      http.get(BASE + "/orders?total=gt.50")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].total", is("89.97")));
    }

    @Test
    @DisplayName("GET orders with FK filter: ?user_id=eq.1")
    void filterByFk() throws Exception {
      http.get(BASE + "/orders?user_id=eq.1")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)));
    }
  }

  // ─── Users table ───────────────────────────────────────────────────────────

  @Nested
  @Order(5)
  class UsersTable {

    @Test
    @DisplayName("GET users")
    void getUsers() throws Exception {
      http.get(BASE + "/users")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].email").exists());
    }

    @Test
    @DisplayName("GET users with email filter")
    void filterByEmail() throws Exception {
      http.get(BASE + "/users?email=eq.alice@test.com")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].name", is("Alice")));
    }
  }

  // ─── Composite Key Table ───────────────────────────────────────────────────

  @Nested
  @Order(6)
  class CompositeKeys {

    @Test
    @DisplayName("GET tags returns all tags")
    void getTags() throws Exception {
      http.get(BASE + "/tags")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("GET tags with compound filter")
    void filterCompound() throws Exception {
      http.get(BASE + "/tags?entity_type=eq.product&entity_id=eq.1")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2))); // popular, sale
    }
  }

  // ─── Settings (JSONB) ──────────────────────────────────────────────────────

  // ─── Accept-Profile / Content-Profile ────────────────────────────────────

  @Nested
  @Order(7)
  class SchemaProfile {

    @Test
    @DisplayName("GET without Accept-Profile uses default schema (public)")
    void defaultSchema() throws Exception {
      http.get(BASE + "/products")
          .expect(status().isOk())
          .expect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET with Accept-Profile: public explicitly selects schema")
    void explicitProfile() throws Exception {
      http.get(BASE + "/products", "Accept-Profile", "public")
          .expect(status().isOk())
          .expect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET with unknown Accept-Profile returns 404")
    void unknownProfile() throws Exception {
      http.get(BASE + "/products", "Accept-Profile", "nonexistent_schema")
          .expect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE without filter returns 400")
    void deleteWithoutFilter() throws Exception {
      http.delete(BASE + "/products")
          .expect(status().isBadRequest())
          .expect(jsonPath("$.error", containsString("filter")));
    }

    @Test
    @DisplayName("PATCH without filter returns 400")
    void patchWithoutFilter() throws Exception {
      http.send("PATCH", BASE + "/products", "{\"price\":1}", "Content-Type", "application/json")
          .expect(status().isBadRequest())
          .expect(jsonPath("$.error", containsString("filter")));
    }

    @Test
    @DisplayName("DELETE with an or= group is refused, not run with the group dropped")
    void deleteWithOrGroupIsRefused() throws Exception {
      http.delete(BASE + "/products?price=gte.0&or=(name.eq.Widget,name.eq.Gadget)")
          .expect(status().isBadRequest())
          .expect(jsonPath("$.error", containsString("or")));
      http.get(BASE + "/products").expect(status().isOk()).expect(jsonPath("$.data", hasSize(5)));
    }

    @Test
    @DisplayName("DELETE with a repeated or= whose first value is empty is refused and deletes nothing")
    void deleteWithRepeatedOrGroupIsRefused() throws Exception {
      // rolled back, so a request that is wrongly run cannot hurt the rows the other tests read
      String[] rollback = {"Prefer", "tx=rollback"};
      http.delete(BASE + "/tags?entity_id=gte.0&or=&or=(tag.eq.popular)", rollback)
          .expect(status().isBadRequest())
          .expect(jsonPath("$.error", containsString("or")));
      http.delete(BASE + "/tags?entity_id=gte.0&or=", rollback)
          .expect(status().isBadRequest());
      http.delete(BASE + "/tags?entity_id=gte.0&not.or=&and=(tag.eq.popular)", rollback)
          .expect(status().isBadRequest());
      http.get(BASE + "/tags").expect(status().isOk()).expect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("PATCH with an and= group is refused, not run with the group dropped")
    void patchWithAndGroupIsRefused() throws Exception {
      http.send("PATCH", BASE + "/products?price=gte.0&and=(name.eq.Widget,price.gt.1)", "{\"price\":1}",
              "Content-Type", "application/json")
          .expect(status().isBadRequest())
          .expect(jsonPath("$.error", containsString("and")));
    }

    @Test
    @DisplayName("POST with Content-Profile selects schema for write")
    void writeWithProfile() throws Exception {
      http.post(BASE + "/products", "{\"name\":\"Profile Test\",\"price\":1.00}", "Content-Type", "application/json", "Content-Profile", "public", "Prefer", "return=representation")
          .expect(status().isCreated())
          .expect(jsonPath("$.data.name", is("Profile Test")));
    }
  }

  // ─── Settings (JSONB) ──────────────────────────────────────────────────────

  @Nested
  @Order(8)
  class BulkAndUpsertOperations {

    @Test
    @DisplayName("POST with array body creates multiple records")
    void bulkCreate() throws Exception {
      http.post(BASE + "/products", "[{\"name\":\"Bulk1\",\"price\":1.00},{\"name\":\"Bulk2\",\"price\":2.00}]", "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("bulk insert into columns named with a space or a quote")
    void bulkInsertColumnNamesWithSpaces() throws Exception {
      http.post(BASE + "/spaced_columns",
              "[{\"first name\":\"Ada\",\"o'neil\":\"x\"},{\"first name\":\"Bob\",\"o'neil\":\"y\"}]",
              "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[1]['first name']", is("Bob")));
    }

    @Test
    @DisplayName("bulk insert columns whose names sanitise alike each keep their own value")
    void bulkInsertCollidingColumnNames() throws Exception {
      http.post(BASE + "/colliding_columns",
              "[{\"a_b_2\":\"one\",\"a b\":\"two\",\"a_b\":\"three\"},"
                      + "{\"a_b_2\":\"four\",\"a b\":\"five\",\"a_b\":\"six\"}]",
              "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].a_b_2", is("one")))
          .expect(jsonPath("$.data[0]['a b']", is("two")))
          .expect(jsonPath("$.data[0].a_b", is("three")))
          .expect(jsonPath("$.data[1].a_b_2", is("four")))
          .expect(jsonPath("$.data[1]['a b']", is("five")))
          .expect(jsonPath("$.data[1].a_b", is("six")));
    }

    @Test
    @DisplayName("POST with Prefer: resolution=merge-duplicates upserts")
    void upsert() throws Exception {
      http.post(BASE + "/products", "{\"id\":1,\"name\":\"Upserted Widget\",\"price\":99.99}", "Content-Type", "application/json", "Prefer", "return=representation, resolution=merge-duplicates")
          .expect(status().isCreated())
          .expect(jsonPath("$.data.name", is("Upserted Widget")));
    }

    @Test
    @DisplayName("PUT replaces a record")
    void putReplace() throws Exception {
      http.send("PUT", BASE + "/products?id=eq.2", "{\"name\":\"Replaced\",\"price\":0.01}", "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Replaced")));
    }
  }

  @Nested
  @Order(9)
  class ResourceEmbedding {

    @Test
    @DisplayName("GET users with embedded orders (reverse FK): ?select=id,name,orders(id,total)")
    void reverseFK_oneToMany() throws Exception {
      http.get(BASE + "/users?select=id,name,orders(id,total)&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Alice")))
          .expect(jsonPath("$.data[0].orders", hasSize(2)));
    }

    @Test
    @DisplayName("GET orders with embedded user (forward FK): ?select=id,total,users(id,name)")
    void forwardFK_manyToOne() throws Exception {
      http.get(BASE + "/orders?select=id,total,users(id,name)&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].users.name", is("Alice")));
    }

    @Test
    @DisplayName("GET users with deep embed: ?select=id,name,orders(*,products(id,name)) — 3-level chain")
    void deepEmbed_twoLevels() throws Exception {
      http.get(BASE + "/users?select=id,name,orders(id,total,products(id,name))&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is("Alice")))
          .expect(jsonPath("$.data[0].orders", hasSize(2)))
          // Each order has a nested products object (forward FK from orders to products)
          .expect(jsonPath("$.data[0].orders[0].products", notNullValue()))
          .expect(jsonPath("$.data[0].orders[0].products.name", notNullValue()));
    }
  }

  @Nested
  @Order(10)
  class CursorPagination {

    @Test
    @DisplayName("GET with first=2 returns 2 records with hasNextPage")
    void cursorFirst() throws Exception {
      http.get(BASE + "/products?first=2&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.pageInfo.hasNextPage", is(true)));
    }

    @Test
    @DisplayName("GET with first=100 returns all, hasNextPage=false")
    void cursorNoMore() throws Exception {
      http.get(BASE + "/products?first=100&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.pageInfo.hasNextPage", is(false)));
    }

    @Test
    @DisplayName("GET with after=2 skips first 2 records")
    void cursorAfter() throws Exception {
      http.get(BASE + "/products?first=2&after=2&order=id.asc")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].id", greaterThan(2)));
    }
  }

  @Nested
  @Order(11)
  class SingularObject {

    @Test
    @DisplayName("GET with Accept: vnd.pgrst.object+json returns single object")
    void singular() throws Exception {
      http.get(BASE + "/products?id=eq.1", "Accept", "application/vnd.pgrst.object+json")
          .expect(status().isOk())
          .expect(jsonPath("$.id", is(1)))
          .expect(jsonPath("$.name").exists());
    }

    @Test
    @DisplayName("GET singular with 0 rows returns 406")
    void singularNoRows() throws Exception {
      http.get(BASE + "/products?id=eq.99999", "Accept", "application/vnd.pgrst.object+json")
          .expect(status().isNotAcceptable());
    }
  }

  @Nested
  @Order(12)
  class RpcEndpoint {

    @Test
    @DisplayName("POST /api/v1/rpc/add_numbers: an untracked function is not found, even with authentication off")
    void rpcUntracked() throws Exception {
      http.post(BASE + "/rpc/add_numbers", "{\"a\":3,\"b\":4}", "Content-Type", "application/json")
          .expect(status().isNotFound());
      http.get(BASE + "/rpc/add_numbers?a=3&b=4")
          .expect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/rpc/nonexistent returns 404")
    void rpcNotFound() throws Exception {
      http.post(BASE + "/rpc/nonexistent", "{}", "Content-Type", "application/json")
          .expect(status().isNotFound());
    }
  }

  @Nested
  @Order(13)
  class PreferHeaders {

    @Test
    @DisplayName("POST with Prefer: tx=rollback does not persist")
    void txRollback() throws Exception {
      http.post(BASE + "/products", "{\"name\":\"Rollback Item\",\"price\":1.00}", "Content-Type", "application/json", "Prefer", "return=representation, tx=rollback")
          .expect(status().isCreated())
          .expect(jsonPath("$.data.name", is("Rollback Item")));

      http.get(BASE + "/products?name=eq.Rollback Item")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH with Prefer: max-affected=0 rejects any affected rows")
    void maxAffected() throws Exception {
      http.send("PATCH", BASE + "/settings?key=eq.theme", "{\"key\":\"theme\"}", "Content-Type", "application/json", "Prefer", "max-affected=0")
          .expect(status().isBadRequest());
    }

    @Test
    @DisplayName("Preference-Applied header echoes honored preferences")
    void preferenceApplied() throws Exception {
      http.get(BASE + "/products", "Prefer", "count=exact")
          .expect(status().isOk())
          .expect(header().string("Preference-Applied", containsString("count=exact")));
    }
  }

  @Nested
  @Order(14)
  class CsvResponse {

    @Test
    @DisplayName("GET with Accept: text/csv returns CSV")
    void csvResponse() throws Exception {
      http.get(BASE + "/products?select=id,name&limit=2&order=id.asc", "Accept", "text/csv")
          .expect(status().isOk())
          .expect(content().contentTypeCompatibleWith("text/csv"))
          .expect(content().string(containsString("id,name")));
    }
  }

  @Nested
  @Order(15)
  class OrFilterLogic {

    @Test
    @DisplayName("GET with or=(category.eq.tools,category.eq.gadgets)")
    void orFilter() throws Exception {
      http.get(BASE + "/products?or=(category.eq.tools,category.eq.gadgets)")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(3))); // Widget, Thingamajig (tools) + Doohickey (gadgets)
    }

    @Test
    @DisplayName("count=exact with an or= group counts the rows the group keeps, not the whole table")
    void countRespectsOrGroups() throws Exception {
      http.get(BASE + "/products?or=(category.eq.tools,category.eq.gadgets)&limit=1", "Prefer", "count=exact")
          .expect(status().isOk())
          .expect(jsonPath("$.pagination.total", is(3)))
          .expect(header().string("Content-Range", containsString("/3")));
    }
  }

  @Nested
  @Order(10)
  class JsonbTable {

    @Test
    @DisplayName("GET settings returns JSONB data")
    void getSettings() throws Exception {
      http.get(BASE + "/settings")
          .expect(status().isOk())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].value").exists());
    }
  }
  /** PostgREST semantics for a JSON-array POST with a resolution preference (EXC-738); runs last, it rewrites users. */
  @Nested
  @Order(40)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class BulkResolution {

    private static final String MERGE = "return=representation, resolution=merge-duplicates";
    private static final String IGNORE = "return=representation, resolution=ignore-duplicates";

    private void assertUser(int id, String name) {
      http.get(BASE + "/users?id=eq." + id)
          .expect(status().isOk())
          .expect(jsonPath("$.data[0].name", is(name)));
    }

    @Test @Order(1)
    @DisplayName("merge-duplicates on an array updates the rows that exist, inserts the rest, answers every row")
    void bulkMergeDuplicates() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Alicia\",\"email\":\"alice@test.com\"},"
              + "{\"id\":50,\"name\":\"Cara\",\"email\":\"cara@test.com\"}]", "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isCreated())
          .expect(header().string("Preference-Applied", containsString("return=representation")))
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].id", is(1)))
          .expect(jsonPath("$.data[0].name", is("Alicia")))
          .expect(jsonPath("$.data[1].id", is(50)));
      assertUser(1, "Alicia");
      assertUser(50, "Cara");
    }

    @Test @Order(2)
    @DisplayName("merge-duplicates without return=representation answers 201 and no body")
    void bulkMergeWithoutRepresentation() {
      http.post(BASE + "/users", "[{\"id\":50,\"name\":\"Clara\",\"email\":\"cara@test.com\"}]",
              "Content-Type", "application/json", "Prefer", "resolution=merge-duplicates")
          .expect(status().isCreated())
          .expect(content().string(""));
      assertUser(50, "Clara");
    }

    @Test @Order(3)
    @DisplayName("ignore-duplicates on an array inserts only the new rows and answers only those")
    void bulkIgnoreDuplicates() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Ignored\",\"email\":\"alice@test.com\"},"
              + "{\"id\":51,\"name\":\"Dan\",\"email\":\"dan@test.com\"}]", "Content-Type", "application/json", "Prefer", IGNORE)
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(1)))
          .expect(jsonPath("$.data[0].id", is(51)));
      assertUser(1, "Alicia");
    }

    @Test @Order(4)
    @DisplayName("on_conflict names the columns the rows merge on")
    void bulkMergeOnConflictColumns() {
      http.post(BASE + "/users?on_conflict=email", "[{\"name\":\"Bobby\",\"email\":\"bob@test.com\"},"
              + "{\"name\":\"Eve\",\"email\":\"eve@test.com\"}]", "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(2)))
          .expect(jsonPath("$.data[0].id", is(2)))
          .expect(jsonPath("$.data[0].name", is("Bobby")))
          .expect(jsonPath("$.data[1].name", is("Eve")));
      assertUser(2, "Bobby");
    }

    @Test @Order(5)
    @DisplayName("on_conflict with ignore-duplicates skips a row that matches on those columns")
    void bulkIgnoreOnConflictColumns() {
      http.post(BASE + "/users?on_conflict=email", "[{\"name\":\"Nobody\",\"email\":\"bob@test.com\"}]",
              "Content-Type", "application/json", "Prefer", IGNORE)
          .expect(status().isCreated())
          .expect(jsonPath("$.data", hasSize(0)));
      assertUser(2, "Bobby");
    }

    @Test @Order(6)
    @DisplayName("ignore-duplicates on a single object is honoured too, not run as a plain insert")
    void singleIgnoreDuplicates() {
      http.post(BASE + "/users", "{\"id\":1,\"name\":\"Ignored\",\"email\":\"alice@test.com\"}",
              "Content-Type", "application/json", "Prefer", IGNORE)
          .expect(status().isCreated());
      assertUser(1, "Alicia");
    }

    @Test @Order(7)
    @DisplayName("an unknown on_conflict column is a 400")
    void unknownOnConflictColumn() {
      http.post(BASE + "/users?on_conflict=nope", "[{\"id\":1,\"name\":\"x\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isBadRequest())
          .expect(content().string(containsString("nope")));
    }

    @Test @Order(8)
    @DisplayName("merge-duplicates together with ignore-duplicates is refused, not guessed")
    void conflictingResolutions() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"x\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", "resolution=merge-duplicates, resolution=ignore-duplicates")
          .expect(status().isBadRequest());
    }

    @Test @Order(9)
    @DisplayName("a plain array with a duplicate key is still a 409")
    void plainBulkInsertStillConflicts() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"x\",\"email\":\"other@test.com\"}]",
              "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isConflict());
    }

    @Test @Order(10)
    @DisplayName("a failing row rolls the whole merge back")
    void failingRowRollsBack() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Changed\",\"email\":\"alice@test.com\"},"
              + "{\"id\":60,\"name\":null,\"email\":\"nameless@test.com\"}]", "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().is4xxClientError());
      assertUser(1, "Alicia");
      http.get(BASE + "/users?id=eq.60").expect(jsonPath("$.data", hasSize(0)));
    }

    @Test @Order(12)
    @DisplayName("an array whose objects have different keys is refused, and merges nothing (no NULL overwrite)")
    void differingKeysAreRefused() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Changed\",\"email\":\"alice@test.com\"},{\"id\":2,\"name\":\"Bobby\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isBadRequest())
          .expect(content().string(containsString("All object keys must match")));
      http.post(BASE + "/users", "[{\"name\":\"p\",\"email\":\"p@test.com\"},{\"name\":\"q\"}]",
              "Content-Type", "application/json", "Prefer", "return=representation")
          .expect(status().isBadRequest());
      assertUser(1, "Alicia");
    }

    @Test @Order(13)
    @DisplayName("ignore-duplicates targets the primary key: a clash on another unique key is still a 409")
    void ignoreDuplicatesDoesNotSwallowOtherUniqueClashes() {
      http.post(BASE + "/users", "[{\"id\":70,\"name\":\"Dupe\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", IGNORE)
          .expect(status().isConflict());
      http.get(BASE + "/users?id=eq.70").expect(jsonPath("$.data", hasSize(0)));
    }

    @Test @Order(14)
    @DisplayName("Preference-Applied lists the resolution that was honoured")
    void preferenceAppliedNamesTheResolution() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Alicia\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isCreated())
          .expect(header().string("Preference-Applied", allOf(containsString("return=representation"),
              containsString("resolution=merge-duplicates"))));
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"Alicia\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", IGNORE)
          .expect(header().string("Preference-Applied", containsString("resolution=ignore-duplicates")));
    }

    @Test @Order(15)
    @DisplayName("on_conflict naming a column with no unique constraint is a 400, not a 500")
    void onConflictWithoutUniqueConstraint() {
      http.post(BASE + "/users?on_conflict=name", "[{\"name\":\"Alicia\",\"email\":\"z@test.com\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isBadRequest())
          .expect(content().string(containsString("ON CONFLICT")));
    }

    @Test @Order(16)
    @DisplayName("the same key twice in one merge is a 400, not a 500")
    void sameKeyTwiceInOneMerge() {
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"a\",\"email\":\"alice@test.com\"},"
              + "{\"id\":1,\"name\":\"b\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isBadRequest());
      assertUser(1, "Alicia");
    }

    @Test @Order(17)
    @DisplayName("an array of non-objects is a 400, not a 500")
    void arrayOfNonObjects() {
      http.post(BASE + "/users", "[1,2]", "Content-Type", "application/json")
          .expect(status().isBadRequest());
      http.post(BASE + "/users", "[{\"id\":1},\"x\"]", "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isBadRequest());
    }

    @Test @Order(20)
    @DisplayName("values are bound, never spliced into the statement")
    void valuesAreParameters() {
      String hostile = "x'); DROP TABLE users;--";
      http.post(BASE + "/users", "[{\"id\":1,\"name\":\"" + hostile + "\",\"email\":\"alice@test.com\"}]",
              "Content-Type", "application/json", "Prefer", MERGE)
          .expect(status().isCreated())
          .expect(jsonPath("$.data[0].name", is(hostile)));
    }
  }
}
