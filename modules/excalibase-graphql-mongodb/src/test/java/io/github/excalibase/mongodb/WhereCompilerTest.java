package io.github.excalibase.mongodb;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import org.bson.types.ObjectId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhereCompilerTest {

    private static final CollectionDef ORDERS = Fixtures.orders();
    private static final Set<String> ALL = Set.of("owner", "status", "total", "paid", "placedAt", "secret", "id");
    private final WhereCompiler compiler = new WhereCompiler(MongoCapabilities.STANDARD);

    private Cond compile(Map<String, Object> where) {
        return compiler.compile(ORDERS, ALL, where);
    }

    @Test
    void anEmptyFilterKeepsEveryDocument() {
        assertThat(compile(Map.of()).kind()).isEqualTo(Cond.Kind.ALWAYS);
    }

    @Test
    void eqIsAnEqualityOnTheDeclaredField() {
        assertThat(compile(Map.of("status", Map.of("eq", "open"))).doc())
                .isEqualTo(new Document("status", new Document("$eq", "open")));
    }

    @Test
    void neqExcludesNullsLikeSql() {
        assertThat(compile(Map.of("status", Map.of("neq", "open"))).doc().toJson())
                .contains("$ne").contains("null");
    }

    @Test
    void rangesAndMembership() {
        assertThat(compile(Map.of("total", Map.of("gt", 5))).doc())
                .isEqualTo(new Document("total", new Document("$gt", 5)));
        assertThat(compile(Map.of("status", Map.of("in", List.of("a", "b")))).doc())
                .isEqualTo(new Document("status", new Document("$in", List.of("a", "b"))));
    }

    @Test
    void isNullTrueMatchesNullOrMissing() {
        assertThat(compile(Map.of("status", Map.of("isNull", true))).doc())
                .isEqualTo(new Document("status", (Object) null));
    }

    @Test
    void theIdIsComparedAsAnObjectId() {
        String hex = "65f0c0ffee0000000000abcd";
        Document doc = compile(Map.of("id", Map.of("eq", hex))).doc();
        assertThat(doc.get("_id", Document.class).get("$eq")).isInstanceOf(ObjectId.class);
    }

    @Test
    void anInvalidObjectIdIsRefused() {
        assertThatThrownBy(() -> compile(Map.of("id", Map.of("eq", "not-an-id"))))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void aFieldThatIsNotDeclaredIsRefused() {
        assertThatThrownBy(() -> compile(Map.of("nope", Map.of("eq", "x"))))
                .isInstanceOf(RequestException.class).hasMessageContaining("nope");
    }

    @Test
    void aFieldTheCallerMayNotSeeIsRefusedLikeAnUnknownOne() {
        assertThatThrownBy(() -> compiler.compile(ORDERS, Set.of("status"), Map.of("secret", Map.of("eq", "x"))))
                .isInstanceOf(RequestException.class).hasMessageContaining("secret");
    }

    @Test
    void anOperatorTheTypeDoesNotSupportFailsLoudly() {
        assertThatThrownBy(() -> compile(Map.of("paid", Map.of("gt", true))))
                .isInstanceOf(RequestException.class).hasMessageContaining("gt");
    }

    @Test
    void anUnknownOperatorIsRefused() {
        assertThatThrownBy(() -> compile(Map.of("status", Map.of("$where", "sleep(1000)"))))
                .isInstanceOf(RequestException.class);
        assertThatThrownBy(() -> compile(Map.of("status", Map.of("$expr", "1"))))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void eqNullIsRefusedInFavourOfIsNull() {
        HashMap<String, Object> ops = new HashMap<>();
        ops.put("eq", null);
        assertThatThrownBy(() -> compile(Map.of("status", ops)))
                .isInstanceOf(RequestException.class).hasMessageContaining("isNull");
    }

    @Test
    void aValueThatIsAnObjectCanNeverCarryAnOperator() {
        assertThatThrownBy(() -> compile(Map.of("status", Map.of("eq", Map.of("$ne", "x")))))
                .isInstanceOf(RequestException.class);
        assertThatThrownBy(() -> compile(Map.of("status", Map.of("in", List.of(Map.of("$function", "x"))))))
                .isInstanceOf(RequestException.class);
        assertThatThrownBy(() -> compile(Map.of("total", Map.of("eq", "5"))))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void operatorLookingStringsAreOnlyEverLiterals() {
        for (String hostile : List.of("$where", "$expr", "$function", "{\"$ne\": null}", "$$ROOT", "this.a == 1")) {
            Document doc = compile(Map.of("status", Map.of("eq", hostile))).doc();
            assertThat(doc).isEqualTo(new Document("status", new Document("$eq", hostile)));
            assertThat(compile(Map.of("status", Map.of("in", List.of(hostile)))).doc())
                    .isEqualTo(new Document("status", new Document("$in", List.of(hostile))));
        }
    }

    @Test
    void theResultNeverContainsAnOperatorTheCompilerDidNotWrite() {
        Set<String> allowed = Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$and", "$or", "$not");
        Document doc = compile(Map.of("status", Map.of("neq", "$where", "in", List.of("$expr")),
                "total", Map.of("gte", 1, "lt", 9))).doc();
        assertThat(operatorsIn(doc)).isSubsetOf(allowed);
    }

    static Set<String> operatorsIn(Object node) {
        Set<String> found = new HashSet<>();
        if (node instanceof Document d) {
            d.forEach((k, v) -> {
                if (k.startsWith("$")) found.add(k);
                found.addAll(operatorsIn(v));
            });
        } else if (node instanceof Iterable<?> list) {
            list.forEach(e -> found.addAll(operatorsIn(e)));
        }
        return found;
    }
}
