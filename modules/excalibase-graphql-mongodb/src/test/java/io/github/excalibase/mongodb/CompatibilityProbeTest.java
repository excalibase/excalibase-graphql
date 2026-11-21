package io.github.excalibase.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each database accepts, outside the operators the engine itself emits. The engine's own operators are
 * proven by the differential and read tests; this pins the features the ADR weighs for later slices
 * (relationships, schema discovery, realtime) so the ADR's compatibility table is measured, not recalled.
 */
class CompatibilityProbeTest {

    private static Map<String, String> probe(Backend backend) {
        Map<String, String> results = new LinkedHashMap<>();
        try (MongoClient client = backend.client()) {
            MongoDatabase db = client.getDatabase("probe_" + backend.name());
            db.drop();
            MongoCollection<Document> a = db.getCollection("a");
            a.insertMany(List.of(new Document("_id", 1).append("k", 1).append("s", "x y"),
                    new Document("_id", 2).append("k", 2).append("s", "z")));
            db.getCollection("b").insertMany(List.of(new Document("_id", 1).append("k", 1)));

            run(results, "$lookup (localField/foreignField)", () -> agg(a, new Document("$lookup",
                    new Document("from", "b").append("localField", "k").append("foreignField", "k").append("as", "j"))));
            run(results, "$lookup (pipeline + let)", () -> agg(a, new Document("$lookup", new Document("from", "b")
                    .append("let", new Document("kk", "$k")).append("pipeline", List.of(new Document("$match",
                            new Document("$expr", new Document("$eq", List.of("$k", "$$kk")))))).append("as", "j"))));
            run(results, "$match with $expr", () -> agg(a, new Document("$match", new Document("$expr",
                    new Document("$eq", List.of("$k", 1))))));
            run(results, "$match with $where", () -> agg(a, new Document("$match", new Document("$where", "this.k == 1"))));
            run(results, "$addFields with $function", () -> agg(a, new Document("$addFields", new Document("f",
                    new Document("$function", new Document("body", "function(x){return x}").append("args", List.of("$k"))
                            .append("lang", "js"))))));
            run(results, "$facet", () -> agg(a, new Document("$facet", new Document("x", List.of(new Document("$limit", 1))))));
            run(results, "$graphLookup", () -> agg(a, new Document("$graphLookup", new Document("from", "a")
                    .append("startWith", "$k").append("connectFromField", "k").append("connectToField", "k").append("as", "g"))));
            run(results, "$unionWith", () -> agg(a, new Document("$unionWith", "b")));
            run(results, "$sample (schema sampling)", () -> agg(a, new Document("$sample", new Document("size", 1))));
            run(results, "$project with $literal null", () -> agg(a, new Document("$project",
                    new Document("m", new Document("$literal", null)))));
            run(results, "$regex option s (dotall)", () -> agg(a, new Document("$match", new Document("s",
                    new BsonRegularExpression("\\Ax.y\\z", "s")))));
            run(results, "$not with a regex", () -> agg(a, new Document("$match", new Document("s",
                    new Document("$not", new BsonRegularExpression("^x"))))));
            run(results, "$type string", () -> agg(a, new Document("$match", new Document("s", new Document("$type", "string")))));
            run(results, "$jsonSchema validator on createCollection", () -> {
                db.createCollection("validated", new CreateCollectionOptions().validationOptions(new ValidationOptions()
                        .validator(new Document("$jsonSchema", new Document("bsonType", "object")
                                .append("required", List.of("k"))
                                .append("properties", new Document("k", new Document("bsonType", "int")))))));
                return "ok";
            });
            run(results, "listCollections shows the validator", () -> {
                List<Document> found = db.listCollections().into(new ArrayList<>());
                return found.stream().filter(d -> "validated".equals(d.getString("name")))
                        .map(d -> String.valueOf(d.get("options"))).findFirst().orElse("collection not found");
            });
            run(results, "change stream (watch)", () -> {
                try (var cursor = a.watch().maxAwaitTime(1, TimeUnit.SECONDS).cursor()) {
                    cursor.tryNext();
                    return "ok";
                }
            });
        }
        return results;
    }

    private static String agg(MongoCollection<Document> collection, Document stage) {
        return "ok(" + collection.aggregate(List.of(stage)).into(new ArrayList<>()).size() + " docs)";
    }

    private static void run(Map<String, String> results, String name, Callable<String> action) {
        try {
            results.put(name, action.call());
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            results.put(name, "REFUSED: " + message.substring(0, Math.min(140, message.length())));
        }
    }

    @Test
    void mongoAcceptsWhatTheAdrCountsOn() {
        Map<String, String> results = probe(Backend.MONGO);
        results.forEach((name, outcome) -> System.out.println("PROBE mongodb | " + name + " | " + outcome));

        assertThat(results.get("$lookup (localField/foreignField)")).startsWith("ok");
        assertThat(results.get("$sample (schema sampling)")).startsWith("ok");
        assertThat(results.get("change stream (watch)")).as("a standalone mongod has no change streams").startsWith("REFUSED");
    }

    @Test
    void documentDbReportsItsGaps() {
        Map<String, String> results = probe(Backend.DOCUMENTDB);
        results.forEach((name, outcome) -> System.out.println("PROBE documentdb | " + name + " | " + outcome));

        assertThat(results.get("$project with $literal null")).as("the engine's column masks depend on it").startsWith("ok");
        assertThat(results.get("$regex option s (dotall)")).as("LIKE depends on it").startsWith("ok");
    }
}
