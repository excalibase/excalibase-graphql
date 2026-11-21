package io.github.excalibase.mongodb;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCompilerTest {

    private static final CollectionDef ORDERS = Fixtures.orders();
    private static final Cond SECURITY = Cond.of(new Document("owner", new Document("$eq", "u1")));
    private static final Projection PROJECTION = new Projection(Set.of("id", "status", "total"), Set.of());
    private static final String ID_HEX = "65f0c0ffee0000000000abcd";

    private static List<Document> list(Cond security, Cond user, List<SortKey> order, int limit, int offset,
                                       Projection projection) {
        return PipelineCompiler.list(ORDERS, security, new ListQuery(user, order, limit, offset), projection, 30);
    }

    private static List<Document> plain(int limit, int offset) {
        return list(SECURITY, Cond.ALWAYS, List.of(), limit, offset, PROJECTION);
    }

    @Test
    void theSecurityMatchIsTheFirstStageAndTheUsersFilterANewOne() {
        Cond user = Cond.of(new Document("status", new Document("$eq", "open")));

        List<Document> stages = list(SECURITY, user, List.of(), 10, 0, PROJECTION);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", SECURITY.doc()));
        assertThat(stages.get(1)).isEqualTo(new Document("$match", user.doc()));
    }

    @Test
    void theSecurityStageIsPresentEvenWhenNothingRestrictsTheCaller() {
        List<Document> stages = list(Cond.ALWAYS, Cond.ALWAYS, List.of(), 10, 0, PROJECTION);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", new Document()));
    }

    @Test
    void sortSkipLimitThenProjectInThatOrder() {
        List<Document> stages = list(SECURITY, Cond.ALWAYS, List.of(new SortKey("total", false)), 10, 20, PROJECTION);

        assertThat(stages.stream().map(stage -> stage.keySet().iterator().next()).toList())
                .containsExactly("$match", "$match", "$sort", "$skip", "$limit", "$project");
        assertThat(stages.get(2).get("$sort", Document.class).toJson()).isEqualTo("{\"total\": -1, \"_id\": 1}");
        assertThat(stages.get(3)).containsEntry("$skip", 20L);
        assertThat(stages.get(4)).containsEntry("$limit", 10L);
    }

    @Test
    void theOrderIsAlwaysStableByTheId() {
        List<Document> stages = plain(10, 0);

        assertThat(stages.get(2).get("$sort", Document.class).toJson()).isEqualTo("{\"_id\": 1}");
    }

    @Test
    void projectionKeepsOnlyWhatWasAskedForAndWhatMayBeSeen() {
        List<Document> stages = list(SECURITY, Cond.ALWAYS, List.of(), 10, 0,
                new Projection(Set.of("status"), Set.of("owner")));

        Document project = stages.getLast().get("$project", Document.class);
        assertThat(project).containsEntry("status", 1).containsEntry("_id", 0)
                .containsEntry("owner", new Document("$literal", null)).doesNotContainKey("secret");
    }

    @Test
    void theIdIsProjectedWhenAskedFor() {
        assertThat(plain(10, 0).getLast().get("$project", Document.class)).containsEntry("_id", 1);
    }

    @Test
    void aProjectionOfNothingStillIncludesInsteadOfExcluding() {
        List<Document> stages = list(SECURITY, Cond.ALWAYS, List.of(), 10, 0, new Projection(Set.of(), Set.of()));

        assertThat(stages.getLast().get("$project", Document.class)).isEqualTo(new Document("_id", 1));
    }

    @Test
    void limitIsBoundedByTheCap() {
        assertThatThrownBy(() -> plain(31, 0)).isInstanceOf(RequestException.class).hasMessageContaining("limit");
    }

    @Test
    void limitMustBePositive() {
        assertThatThrownBy(() -> plain(0, 0)).isInstanceOf(RequestException.class).hasMessageContaining("limit");
    }

    @Test
    void offsetMustNotBeNegative() {
        assertThatThrownBy(() -> plain(5, -1)).isInstanceOf(RequestException.class).hasMessageContaining("offset");
    }

    @Test
    void offsetIsBounded() {
        assertThatThrownBy(() -> plain(5, PipelineCompiler.MAX_OFFSET + 1))
                .isInstanceOf(RequestException.class).hasMessageContaining("offset");
    }

    @Test
    void anOrderingByAFieldThatIsNotDeclaredIsRefused() {
        List<SortKey> order = List.of(new SortKey("nope", true));

        assertThatThrownBy(() -> list(SECURITY, Cond.ALWAYS, order, 5, 0, PROJECTION))
                .isInstanceOf(RequestException.class).hasMessageContaining("nope");
    }

    @Test
    void getByIdMatchesTheIdAfterTheSecurityStageAndTakesOne() {
        List<Document> stages = PipelineCompiler.byId(ORDERS, SECURITY, ID_HEX, PROJECTION);

        assertThat(stages.get(0)).isEqualTo(new Document("$match", SECURITY.doc()));
        assertThat(stages.get(1).get("$match", Document.class).get("_id", Document.class))
                .containsEntry("$eq", new ObjectId(ID_HEX));
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

        assertThat(stages.get(1).get("$match", Document.class).get("_id", Document.class)).containsEntry("$eq", "$where");
    }
}
