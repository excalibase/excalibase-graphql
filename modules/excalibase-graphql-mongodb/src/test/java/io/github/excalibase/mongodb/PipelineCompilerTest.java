package io.github.excalibase.mongodb;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCompilerTest {

    private static final CollectionDef ORDERS = Fixtures.orders();
    private static final Cond SECURITY = Cond.of(new Document("owner", new Document("$eq", "u1")));
    private static final Projection PROJECTION = new Projection(Set.of("id", "status", "total"), Set.of());

    @Test
    void theSecurityMatchIsTheFirstStageAndTheUsersFilterANewOne() {
        Cond user = Cond.of(new Document("status", new Document("$eq", "open")));

        List<Document> stages = PipelineCompiler.list(ORDERS, SECURITY, user, List.of(), 10, 0, PROJECTION, 30);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", SECURITY.doc()));
        assertThat(stages.get(1)).isEqualTo(new Document("$match", user.doc()));
    }

    @Test
    void theSecurityStageIsPresentEvenWhenNothingRestrictsTheCaller() {
        List<Document> stages = PipelineCompiler.list(ORDERS, Cond.ALWAYS, Cond.ALWAYS, List.of(), 10, 0, PROJECTION, 30);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", new Document()));
    }

    @Test
    void sortSkipLimitThenProjectInThatOrder() {
        List<Document> stages = PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS,
                List.of(new SortKey("total", false)), 10, 20, PROJECTION, 30);

        assertThat(stages.stream().map(d -> d.keySet().iterator().next()).toList())
                .containsExactly("$match", "$match", "$sort", "$skip", "$limit", "$project");
        assertThat(stages.get(2).get("$sort", Document.class).toJson()).isEqualTo("{\"total\": -1, \"_id\": 1}");
        assertThat(stages.get(3).get("$skip")).isEqualTo(20L);
        assertThat(stages.get(4).get("$limit")).isEqualTo(10L);
    }

    @Test
    void theOrderIsAlwaysStableByTheId() {
        List<Document> stages = PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 10, 0, PROJECTION, 30);

        assertThat(stages.get(2).get("$sort", Document.class).toJson()).isEqualTo("{\"_id\": 1}");
    }

    @Test
    void projectionKeepsOnlyWhatWasAskedForAndWhatMayBeSeen() {
        List<Document> stages = PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 10, 0,
                new Projection(Set.of("status"), Set.of("owner")), 30);

        Document project = stages.getLast().get("$project", Document.class);
        assertThat(project.get("status")).isEqualTo(1);
        assertThat(project.get("_id")).isEqualTo(0);
        assertThat(project.get("owner")).isEqualTo(new Document("$literal", null));
        assertThat(project).doesNotContainKey("secret");
    }

    @Test
    void theIdIsProjectedWhenAskedFor() {
        List<Document> stages = PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 10, 0, PROJECTION, 30);

        assertThat(stages.getLast().get("$project", Document.class).get("_id")).isEqualTo(1);
    }

    @Test
    void limitAndOffsetAreBounded() {
        assertThatThrownBy(() -> PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 31, 0, PROJECTION, 30))
                .isInstanceOf(RequestException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 0, 0, PROJECTION, 30))
                .isInstanceOf(RequestException.class);
        assertThatThrownBy(() -> PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 5, -1, PROJECTION, 30))
                .isInstanceOf(RequestException.class).hasMessageContaining("offset");
        assertThatThrownBy(() -> PipelineCompiler.list(ORDERS, SECURITY, Cond.ALWAYS, List.of(), 5,
                PipelineCompiler.MAX_OFFSET + 1, PROJECTION, 30))
                .isInstanceOf(RequestException.class).hasMessageContaining("offset");
    }

    @Test
    void getByIdMatchesTheIdAfterTheSecurityStageAndTakesOne() {
        String hex = "65f0c0ffee0000000000abcd";

        List<Document> stages = PipelineCompiler.byId(ORDERS, SECURITY, hex, PROJECTION);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", SECURITY.doc()));
        assertThat(stages.get(1).get("$match", Document.class).get("_id", Document.class).get("$eq")).isEqualTo(new ObjectId(hex));
        assertThat(stages.get(2)).isEqualTo(new Document("$limit", 1L));
    }

    @Test
    void anIdThatCannotBeParsedIsRefusedNotWidened() {
        assertThatThrownBy(() -> PipelineCompiler.byId(ORDERS, SECURITY, "{\"$ne\":null}", PROJECTION))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void anIdOfStringKindIsTakenAsTheLiteralItIs() {
        CollectionDef byName = new CollectionDef("tags", FieldKind.STRING, Map.of("label", FieldKind.STRING));

        List<Document> stages = PipelineCompiler.byId(byName, Cond.ALWAYS, "$where", new Projection(Set.of("id"), Set.of()));

        assertThat(stages.get(1).get("$match", Document.class).get("_id", Document.class).get("$eq")).isEqualTo("$where");
    }
}
