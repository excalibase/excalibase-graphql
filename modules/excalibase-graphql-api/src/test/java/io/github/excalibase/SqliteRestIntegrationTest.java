package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

/** The REST API over a SQLite file: reads and filters, writes with their read-back, and an operator SQLite lacks. */
class SqliteRestIntegrationTest extends EngineIntegrationTest {

    private static final SqliteFile DATABASE = SqliteFile.create(SqliteFile.SHOP);

    @Override
    public Map<String, String> getProperties() {
        return DATABASE.engine("shop");
    }

    @Test
    void get_filtersOrdersAndPages() {
        http.get("/shop/api/v1/customers?name=in.(Bob,carol)&order=id.desc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].name", contains("carol", "Bob")));
        http.get("/shop/api/v1/customers?email=is.null").expect(jsonPath("$.data", hasSize(1)));
        http.get("/shop/api/v1/customers?id=in.(1,2)&limit=1&order=id").expect(jsonPath("$.data", hasSize(1)));
        http.get("/shop/api/v1/customers?name=ilike.a*").expect(jsonPath("$.data[*].name", contains("Alice")));
    }

    @Test
    void post_patch_delete_answerTheWrittenRows() {
        http.send("POST", "/shop/api/v1/customers", "{\"name\":\"Rest\",\"email\":\"rest@x.io\"}", "Prefer",
                        "return=representation", "Content-Type", "application/json")
                .expect(status().isCreated())
                .expect(jsonPath("$.data.name").value("Rest"));
        http.send("PATCH", "/shop/api/v1/customers?email=eq.rest@x.io", "{\"name\":\"Renamed\"}", "Prefer",
                        "return=representation", "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].name").value("Renamed"));
        http.send("DELETE", "/shop/api/v1/customers?email=eq.rest@x.io", null, "Prefer", "return=representation")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].name").value("Renamed"));
    }

    @Test
    void upsert_onTheKey_updatesTheRow() {
        http.send("POST", "/shop/api/v1/tags", "{\"order_id\":3,\"tag\":\"u\"}", "Prefer", "return=representation",
                "Content-Type", "application/json").expect(status().isCreated());
        http.send("POST", "/shop/api/v1/tags", "{\"order_id\":3,\"tag\":\"u\"}", "Prefer",
                "return=representation, resolution=merge-duplicates", "Content-Type", "application/json")
                .expect(status().is2xxSuccessful());
        http.get("/shop/api/v1/tags?order_id=eq.3&tag=eq.u").expect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void aTimestampWrittenByTheDefault_filtersAsTheInstantItIs() {
        http.get("/shop/api/v1/events?at=gt.2026-01-01T11:00:00Z").expect(jsonPath("$.data[*].label", contains("late")));
        http.get("/shop/api/v1/events?at=lt.2026-01-01T11:00:00Z").expect(jsonPath("$.data[*].label", contains("early")));
    }

    @Test
    void duplicateKey_isAConflict() {
        http.send("POST", "/shop/api/v1/customers", "{\"name\":\"Dup\",\"email\":\"alice@x.io\"}", "Prefer",
                        "return=representation", "Content-Type", "application/json")
                .expect(status().isConflict())
                .expect(jsonPath("$.code").value("unique_violation"));
    }

    @Test
    void anOperatorSqliteLacks_isRefusedByName_neverIgnored() {
        http.get("/shop/api/v1/customers?name=match.^A").expect(status().isBadRequest());
        http.get("/shop/api/v1/customers?meta=cs.{\"vip\":true}").expect(status().isBadRequest());
    }
}
