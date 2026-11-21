package io.github.excalibase.mongodb;

import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.spi.QueryEngine;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Seeds the orders collection into a real database and serves it through the engine. */
abstract class AbstractEngineTest {

    static final String ID_1 = "65f0c0ffee0000000000a001";
    static final String ID_2 = "65f0c0ffee0000000000a002";
    static final String ID_3 = "65f0c0ffee0000000000a003";
    static final String ID_4 = "65f0c0ffee0000000000a004";
    static final String ID_5 = "65f0c0ffee0000000000a005";

    private QueryEngine engine;

    abstract Backend backend();

    /** The declared type of {@code _id} in the seeded collection. */
    String idKind() {
        return "objectId";
    }

    String database() {
        return "t_" + getClass().getSimpleName().toLowerCase();
    }

    static List<Document> orders() {
        return List.of(
                order(ID_1, "u1", "open", 10, true, 1_700_000_000_000L, "s1"),
                order(ID_2, "u1", "closed", 20, false, 1_700_000_100_000L, "s2"),
                order(ID_3, "u2", "open", 30, true, 1_700_000_200_000L, "s3"),
                order(ID_4, "u2", "$where", 40, false, 1_700_000_300_000L, "s4"),
                new Document("_id", new ObjectId(ID_5)).append("total", 50));
    }

    private static Document order(String id, String owner, String status, int total, boolean paid, long placed, String secret) {
        return new Document("_id", new ObjectId(id)).append("owner", owner).append("status", status)
                .append("total", total).append("paid", paid).append("placedAt", java.time.Instant.ofEpochMilli(placed)).append("secret", secret);
    }

    QueryEngine engine(List<Policy> policies, List<ColumnPolicy> columnPolicies, List<String> open) {
        return engineOver(orders(), policies, columnPolicies, open);
    }

    QueryEngine engineOver(List<Document> documents, List<Policy> policies, List<ColumnPolicy> columnPolicies,
                           List<String> open) {
        backend().seed(database(), "orders", documents);
        Map<String, Object> settings = new HashMap<>();
        settings.put("uri", backend().uri());
        settings.put("database", database());
        settings.put("collections", List.of(Map.of("name", "orders", "id", idKind(), "fields", orderFields())));
        settings.put("policies", policies);
        settings.put("columnPolicies", columnPolicies);
        settings.put("openCollections", open);
        if (backend().sslContext() != null) {
            settings.put("sslContext", backend().sslContext());
        }
        engine = new MongoQueryEngineProvider().create(settings);
        return engine;
    }

    QueryEngine openEngine() {
        return engine(List.of(), List.of(), List.of("orders"));
    }

    private static Map<String, Object> orderFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("owner", "string");
        fields.put("status", "string");
        fields.put("total", "int");
        fields.put("paid", "boolean");
        fields.put("placedAt", "datetime");
        fields.put("secret", "string");
        return fields;
    }

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    static UserContext caller(String userId) {
        return Fixtures.caller(userId);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> response, String field) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return data == null ? new ArrayList<>() : (List<Map<String, Object>>) data.get(field);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> one(Map<String, Object> response, String field) {
        return (Map<String, Object>) ((Map<String, Object>) response.get("data")).get(field);
    }

    @SuppressWarnings("unchecked")
    static String error(Map<String, Object> response) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        return errors == null || errors.isEmpty() ? null : String.valueOf(errors.getFirst().get("message"));
    }
}
