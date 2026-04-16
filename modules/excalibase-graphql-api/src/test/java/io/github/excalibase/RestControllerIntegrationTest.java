package io.github.excalibase;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class RestControllerIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-rest-test.sql");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);

    registry.add("app.database-type", () -> "postgres");
    registry.add("app.max-rows", () -> 30);
  }

  @Autowired
  private MockMvc mockMvc;

  private static final String BASE = "/api/v1";

  // ─── GET (list) ────────────────────────────────────────────────────────────

  @Nested
  @Order(1)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class ReadOperations {

    @Test @Order(1)
    @DisplayName("GET returns all records")
    void getAll() throws Exception {
      mockMvc.perform(get(BASE + "/products"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(5)))
          .andExpect(jsonPath("$.data[0].name").exists());
    }

    @Test @Order(2)
    @DisplayName("GET with select: ?select=id,name returns only those columns")
    void selectColumns() throws Exception {
      mockMvc.perform(get(BASE + "/products?select=id,name"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].id").exists())
          .andExpect(jsonPath("$.data[0].name").exists())
          .andExpect(jsonPath("$.data[0].price").doesNotExist());
    }

    @Test @Order(3)
    @DisplayName("GET with limit and offset")
    void pagination() throws Exception {
      mockMvc.perform(get(BASE + "/products?limit=2&offset=1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test @Order(4)
    @DisplayName("GET with Prefer: count=exact returns Content-Range")
    void countExact() throws Exception {
      mockMvc.perform(get(BASE + "/products")
              .header("Prefer", "count=exact"))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Range", containsString("/5")))
          .andExpect(jsonPath("$.pagination.total", is(5)));
    }

    @Test @Order(5)
    @DisplayName("GET nonexistent table returns 404")
    void unknownTable() throws Exception {
      mockMvc.perform(get(BASE + "/nonexistent"))
          .andExpect(status().isNotFound());
    }

    @Test @Order(6)
    @DisplayName("GET view returns data")
    void queryView() throws Exception {
      mockMvc.perform(get(BASE + "/expensive_products"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2))); // Gadget(19.99), Gizmo(29.99)
    }
  }

  // ─── Filtering ─────────────────────────────────────────────────────────────

  @Nested
  @Order(2)
  class Filtering {

    @Test
    @DisplayName("eq: ?name=eq.Widget")
    void filterEq() throws Exception {
      mockMvc.perform(get(BASE + "/products?name=eq.Widget"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].name", is("Widget")));
    }

    @Test
    @DisplayName("neq: ?name=neq.Widget returns non-Widget products")
    void filterNeq() throws Exception {
      mockMvc.perform(get(BASE + "/products?name=neq.Widget"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("gt: ?price=gt.15 returns expensive products")
    void filterGt() throws Exception {
      mockMvc.perform(get(BASE + "/products?price=gt.15"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2))); // Gadget(19.99), Gizmo(29.99)
    }

    @Test
    @DisplayName("gte: ?price=gte.19.99")
    void filterGte() throws Exception {
      mockMvc.perform(get(BASE + "/products?price=gte.19.99"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("lt: ?price=lt.10")
    void filterLt() throws Exception {
      mockMvc.perform(get(BASE + "/products?price=lt.10"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2))); // Widget(9.99), Thingamajig(4.99)
    }

    @Test
    @DisplayName("lte: ?price=lte.9.99")
    void filterLte() throws Exception {
      mockMvc.perform(get(BASE + "/products?price=lte.9.99"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("is.null: ?category=is.null")
    void filterIsNull() throws Exception {
      // No null categories in our data, should return 0
      mockMvc.perform(get(BASE + "/products?category=is.null"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("is.true: ?in_stock=is.true")
    void filterIsTrue() throws Exception {
      mockMvc.perform(get(BASE + "/products?in_stock=is.true"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(4))); // All except Gizmo
    }

    @Test
    @DisplayName("is.false: ?in_stock=is.false")
    void filterIsFalse() throws Exception {
      mockMvc.perform(get(BASE + "/products?in_stock=is.false"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].name", is("Gizmo")));
    }

    @Test
    @DisplayName("like: ?name=like.*adget")
    void filterLike() throws Exception {
      mockMvc.perform(get(BASE + "/products?name=like.*adget"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].name", is("Gadget")));
    }

    @Test
    @DisplayName("not.eq: ?name=not.eq.Widget")
    void filterNegation() throws Exception {
      mockMvc.perform(get(BASE + "/products?name=not.eq.Widget"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("multiple filters: ?category=eq.electronics&in_stock=is.true")
    void multipleFilters() throws Exception {
      mockMvc.perform(get(BASE + "/products?category=eq.electronics&in_stock=is.true"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].name", is("Gadget")));
    }
  }

  // ─── Ordering ──────────────────────────────────────────────────────────────

  @Nested
  @Order(3)
  class Ordering {

    @Test
    @DisplayName("order=price.asc")
    void orderAsc() throws Exception {
      mockMvc.perform(get(BASE + "/products?order=price.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Thingamajig")))
          .andExpect(jsonPath("$.data[4].name", is("Gizmo")));
    }

    @Test
    @DisplayName("order=price.desc")
    void orderDesc() throws Exception {
      mockMvc.perform(get(BASE + "/products?order=price.desc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Gizmo")))
          .andExpect(jsonPath("$.data[0].price", is(29.99)));
    }

    @Test
    @DisplayName("order=name.asc with limit")
    void orderWithLimit() throws Exception {
      mockMvc.perform(get(BASE + "/products?order=name.asc&limit=2"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].name", is("Doohickey")));
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
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation")
              .content("{\"name\":\"Created Item\",\"price\":55.00,\"category\":\"test\"}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name", is("Created Item")))
          .andExpect(jsonPath("$.data.price", is(55.0)))
          .andExpect(jsonPath("$.data.id").exists());
    }

    @Test @Order(2)
    @DisplayName("POST creates record without return (201 empty body)")
    void createWithoutReturn() throws Exception {
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"Silent Create\",\"price\":1.00}"))
          .andExpect(status().isCreated());
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
      mockMvc.perform(patch(BASE + "/products?id=eq.1")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation")
              .content("{\"price\":99.99}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].price", is(99.99)));
    }

    @Test @Order(2)
    @DisplayName("PATCH updates multiple columns")
    void patchMultipleColumns() throws Exception {
      mockMvc.perform(patch(BASE + "/products?id=eq.2")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation")
              .content("{\"name\":\"Updated Gadget\",\"price\":25.00}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Updated Gadget")))
          .andExpect(jsonPath("$.data[0].price", is(25.0)));
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
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"To Delete\",\"price\":0.01}"))
          .andExpect(status().isCreated());

      mockMvc.perform(delete(BASE + "/products?name=eq.To Delete")
              .header("Prefer", "return=representation"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("To Delete")));
    }
  }

  // ─── Orders table (FK relationship data) ───────────────────────────────────

  @Nested
  @Order(4)
  class OrdersTable {

    @Test
    @DisplayName("GET orders returns order data")
    void getOrders() throws Exception {
      mockMvc.perform(get(BASE + "/orders"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("GET orders with filter: ?status=eq.completed")
    void filterByStatus() throws Exception {
      mockMvc.perform(get(BASE + "/orders?status=eq.completed"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("GET orders with numeric filter: ?total=gt.50")
    void filterByTotal() throws Exception {
      mockMvc.perform(get(BASE + "/orders?total=gt.50"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].total", is(89.97)));
    }

    @Test
    @DisplayName("GET orders with FK filter: ?user_id=eq.1")
    void filterByFk() throws Exception {
      mockMvc.perform(get(BASE + "/orders?user_id=eq.1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }
  }

  // ─── Users table ───────────────────────────────────────────────────────────

  @Nested
  @Order(5)
  class UsersTable {

    @Test
    @DisplayName("GET users")
    void getUsers() throws Exception {
      mockMvc.perform(get(BASE + "/users"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].email").exists());
    }

    @Test
    @DisplayName("GET users with email filter")
    void filterByEmail() throws Exception {
      mockMvc.perform(get(BASE + "/users?email=eq.alice@test.com"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].name", is("Alice")));
    }
  }

  // ─── Composite Key Table ───────────────────────────────────────────────────

  @Nested
  @Order(6)
  class CompositeKeys {

    @Test
    @DisplayName("GET tags returns all tags")
    void getTags() throws Exception {
      mockMvc.perform(get(BASE + "/tags"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    @DisplayName("GET tags with compound filter")
    void filterCompound() throws Exception {
      mockMvc.perform(get(BASE + "/tags?entity_type=eq.product&entity_id=eq.1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2))); // popular, sale
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
      mockMvc.perform(get(BASE + "/products"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET with Accept-Profile: public explicitly selects schema")
    void explicitProfile() throws Exception {
      mockMvc.perform(get(BASE + "/products")
              .header("Accept-Profile", "public"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET with unknown Accept-Profile returns 404")
    void unknownProfile() throws Exception {
      mockMvc.perform(get(BASE + "/products")
              .header("Accept-Profile", "nonexistent_schema"))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE without filter returns 400")
    void deleteWithoutFilter() throws Exception {
      mockMvc.perform(delete(BASE + "/products"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error", containsString("filter")));
    }

    @Test
    @DisplayName("PATCH without filter returns 400")
    void patchWithoutFilter() throws Exception {
      mockMvc.perform(patch(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"price\":1}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error", containsString("filter")));
    }

    @Test
    @DisplayName("POST with Content-Profile selects schema for write")
    void writeWithProfile() throws Exception {
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Content-Profile", "public")
              .header("Prefer", "return=representation")
              .content("{\"name\":\"Profile Test\",\"price\":1.00}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name", is("Profile Test")));
    }
  }

  // ─── Settings (JSONB) ──────────────────────────────────────────────────────

  @Nested
  @Order(8)
  class BulkAndUpsertOperations {

    @Test
    @DisplayName("POST with array body creates multiple records")
    void bulkCreate() throws Exception {
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation")
              .content("[{\"name\":\"Bulk1\",\"price\":1.00},{\"name\":\"Bulk2\",\"price\":2.00}]"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("POST with Prefer: resolution=merge-duplicates upserts")
    void upsert() throws Exception {
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation, resolution=merge-duplicates")
              .content("{\"id\":1,\"name\":\"Upserted Widget\",\"price\":99.99}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name", is("Upserted Widget")));
    }

    @Test
    @DisplayName("PUT replaces a record")
    void putReplace() throws Exception {
      mockMvc.perform(put(BASE + "/products?id=eq.2")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation")
              .content("{\"name\":\"Replaced\",\"price\":0.01}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Replaced")));
    }
  }

  @Nested
  @Order(9)
  class ResourceEmbedding {

    @Test
    @DisplayName("GET users with embedded orders (reverse FK): ?select=id,name,orders(id,total)")
    void reverseFK_oneToMany() throws Exception {
      mockMvc.perform(get(BASE + "/users?select=id,name,orders(id,total)&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Alice")))
          .andExpect(jsonPath("$.data[0].orders", hasSize(2)));
    }

    @Test
    @DisplayName("GET orders with embedded user (forward FK): ?select=id,total,users(id,name)")
    void forwardFK_manyToOne() throws Exception {
      mockMvc.perform(get(BASE + "/orders?select=id,total,users(id,name)&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].users.name", is("Alice")));
    }

    @Test
    @DisplayName("GET users with deep embed: ?select=id,name,orders(*,products(id,name)) — 3-level chain")
    void deepEmbed_twoLevels() throws Exception {
      mockMvc.perform(get(BASE + "/users?select=id,name,orders(id,total,products(id,name))&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].name", is("Alice")))
          .andExpect(jsonPath("$.data[0].orders", hasSize(2)))
          // Each order has a nested products object (forward FK from orders to products)
          .andExpect(jsonPath("$.data[0].orders[0].products", notNullValue()))
          .andExpect(jsonPath("$.data[0].orders[0].products.name", notNullValue()));
    }
  }

  @Nested
  @Order(10)
  class CursorPagination {

    @Test
    @DisplayName("GET with first=2 returns 2 records with hasNextPage")
    void cursorFirst() throws Exception {
      mockMvc.perform(get(BASE + "/products?first=2&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.pageInfo.hasNextPage", is(true)));
    }

    @Test
    @DisplayName("GET with first=100 returns all, hasNextPage=false")
    void cursorNoMore() throws Exception {
      mockMvc.perform(get(BASE + "/products?first=100&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.pageInfo.hasNextPage", is(false)));
    }

    @Test
    @DisplayName("GET with after=2 skips first 2 records")
    void cursorAfter() throws Exception {
      mockMvc.perform(get(BASE + "/products?first=2&after=2&order=id.asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].id", greaterThan(2)));
    }
  }

  @Nested
  @Order(11)
  class SingularObject {

    @Test
    @DisplayName("GET with Accept: vnd.pgrst.object+json returns single object")
    void singular() throws Exception {
      mockMvc.perform(get(BASE + "/products?id=eq.1")
              .accept(org.springframework.http.MediaType.parseMediaType("application/vnd.pgrst.object+json")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id", is(1)))
          .andExpect(jsonPath("$.name").exists());
    }

    @Test
    @DisplayName("GET singular with 0 rows returns 406")
    void singularNoRows() throws Exception {
      mockMvc.perform(get(BASE + "/products?id=eq.99999")
              .accept(org.springframework.http.MediaType.parseMediaType("application/vnd.pgrst.object+json")))
          .andExpect(status().isNotAcceptable());
    }
  }

  @Nested
  @Order(12)
  class RpcEndpoint {

    @Test
    @DisplayName("POST /api/v1/rpc/add_numbers calls function")
    void rpcCall() throws Exception {
      mockMvc.perform(post(BASE + "/rpc/add_numbers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"a\":3,\"b\":4}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result", is(7)));
    }

    @Test
    @DisplayName("POST /api/v1/rpc/nonexistent returns 404")
    void rpcNotFound() throws Exception {
      mockMvc.perform(post(BASE + "/rpc/nonexistent")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @Order(13)
  class PreferHeaders {

    @Test
    @DisplayName("POST with Prefer: tx=rollback does not persist")
    void txRollback() throws Exception {
      mockMvc.perform(post(BASE + "/products")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "return=representation, tx=rollback")
              .content("{\"name\":\"Rollback Item\",\"price\":1.00}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name", is("Rollback Item")));

      mockMvc.perform(get(BASE + "/products?name=eq.Rollback Item"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH with Prefer: max-affected=0 rejects any affected rows")
    void maxAffected() throws Exception {
      mockMvc.perform(patch(BASE + "/settings?key=eq.theme")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Prefer", "max-affected=0")
              .content("{\"key\":\"theme\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Preference-Applied header echoes honored preferences")
    void preferenceApplied() throws Exception {
      mockMvc.perform(get(BASE + "/products")
              .header("Prefer", "count=exact"))
          .andExpect(status().isOk())
          .andExpect(header().string("Preference-Applied", containsString("count=exact")));
    }
  }

  @Nested
  @Order(14)
  class CsvResponse {

    @Test
    @DisplayName("GET with Accept: text/csv returns CSV")
    void csvResponse() throws Exception {
      mockMvc.perform(get(BASE + "/products?select=id,name&limit=2&order=id.asc")
              .accept(MediaType.parseMediaType("text/csv")))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("text/csv"))
          .andExpect(content().string(containsString("id,name")));
    }
  }

  @Nested
  @Order(15)
  class OrFilterLogic {

    @Test
    @DisplayName("GET with or=(category.eq.tools,category.eq.gadgets)")
    void orFilter() throws Exception {
      mockMvc.perform(get(BASE + "/products?or=(category.eq.tools,category.eq.gadgets)"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(3))); // Widget, Thingamajig (tools) + Doohickey (gadgets)
    }
  }

  @Nested
  @Order(10)
  class JsonbTable {

    @Test
    @DisplayName("GET settings returns JSONB data")
    void getSettings() throws Exception {
      mockMvc.perform(get(BASE + "/settings"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].value").exists());
    }
  }
}
