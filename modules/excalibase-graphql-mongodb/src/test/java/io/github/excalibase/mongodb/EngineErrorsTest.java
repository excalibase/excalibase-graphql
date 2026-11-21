package io.github.excalibase.mongodb;

import io.github.excalibase.spi.QueryEngine;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mongodb.MongoException;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine against a fake runner: what a request becomes, and what a failure shows. */
class EngineErrorsTest {

    private static MongoQueryEngine engine(PipelineRunner runner) {
        Catalog catalog = Fixtures.catalog();
        return new MongoQueryEngine(catalog, new RowSecurity(catalog, List.of()), new ColumnSecurity(catalog, List.of()),
                runner, MongoCapabilities.STANDARD, 30, 100);
    }

    private static PipelineRunner recording(List<List<Document>> seen) {
        return new PipelineRunner() {
            @Override public List<Document> run(String collection, List<Document> pipeline) {
                seen.add(pipeline);
                return List.of();
            }
            @Override public void close() {
                // nothing is held
            }
        };
    }

    @Test
    void aDriverFailureShowsNothingInternal() {
        PipelineRunner failing = new PipelineRunner() {
            @Override public List<Document> run(String collection, List<Document> pipeline) {
                throw new MongoException("connect to 10.1.2.3:27017 as admin failed");
            }
            @Override public void close() {
                // nothing is held
            }
        };

        Map<String, Object> response = engine(failing).execute("{ orders { id } }", Map.of(), Fixtures.caller("u"));

        assertThat(response.toString()).contains("Internal server error").doesNotContain("10.1.2.3").doesNotContain("admin");
    }

    @Test
    void eachRootFieldIsOnePipelineWithTheSecurityStageFirst() {
        List<List<Document>> seen = new ArrayList<>();

        engine(recording(seen)).execute("{ a: orders { id } b: orders(limit: 1) { id } }", Map.of(), Fixtures.caller("u"));

        assertThat(seen).hasSize(2);
        seen.forEach(pipeline -> assertThat(pipeline.getFirst().keySet()).containsExactly("$match"));
    }

    @Test
    void aRequestThatFansOutTooWideIsRefusedBeforeAnyQuery() {
        List<List<Document>> seen = new ArrayList<>();
        StringBuilder query = new StringBuilder("{");
        for (int i = 0; i < 60; i++) {
            query.append(" a").append(i).append(": orders { id owner } ");
        }
        query.append("}");

        Map<String, Object> response = engine(recording(seen)).execute(query.toString(), Map.of(), Fixtures.caller("u"));

        assertThat(response.get("errors")).isNotNull();
        assertThat(seen).isEmpty();
    }

    @Test
    void aSelectionWithNoFieldStillProjectsInclusively() {
        List<List<Document>> seen = new ArrayList<>();

        engine(recording(seen)).execute("{ orders { __typename } }", Map.of(), Fixtures.caller("u"));

        assertThat(seen.getFirst().getLast().get("$project", Document.class)).isEqualTo(new Document("_id", 1));
    }

    @Test
    void closeClosesTheRunner() {
        boolean[] closed = {false};
        QueryEngine engine = engine(new PipelineRunner() {
            @Override public List<Document> run(String collection, List<Document> pipeline) { return List.of(); }
            @Override public void close() { closed[0] = true; }
        });

        engine.close();

        assertThat(closed[0]).isTrue();
    }
}
