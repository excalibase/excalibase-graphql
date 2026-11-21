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
                .isEqualTo(new Document("status", Fixtures.scalar("$eq", "open")));
    }

    @Test
    void neqExcludesNullsLikeSql() {
        assertThat(compile(Map.of("status", Map.of("neq", "open"))).doc().toJson())
                .contains("$ne").contains("$type");
    }

    @Test
    void rangesAndMembership() {
        assertThat(compile(Map.of("total", Map.of("gt", 5))).doc())
                .isEqualTo(new Document("total", Fixtures.scalar("$gt", 5)));
        assertThat(compile(Map.of("status", Map.of("in", List.of("a", "b")))).doc())
                .isEqualTo(new Document("status", Fixtures.scalar("$in", List.of("a", "b"))));
    }

    @Test
    void isNullTrueMatchesNullOrMissing() {
        assertThat(compile(Map.of("status", Map.of("isNull", true))).doc())
                .isEqualTo(new Document("status", Fixtures.scalar("$eq", null)));
    }

    @Test
    void theIdIsComparedAsAnObjectId() {
        String hex = "65f0c0ffee0000000000abcd";
        Document doc = compile(Map.of("id", Map.of("eq", hex))).doc();
        assertThat(doc.get("_id", Document.class).get("$eq")).isInstanceOf(ObjectId.class);
    }

    @Test
    void aNumericIdIsComparedAsANumber() {
        CollectionDef numbered = new CollectionDef("n", FieldKind.INT, Map.of());

        assertThat(compiler.compile(numbered, Set.of("id"), Map.of("id", Map.of("eq", 5))).doc())
                .isEqualTo(new Document("_id", Fixtures.scalar("$eq", 5)));
        assertThat(compiler.compile(numbered, Set.of("id"), Map.of("id", Map.of("in", List.of("7")))).doc())
                .isEqualTo(new Document("_id", Fixtures.scalar("$in", List.of(7))));
    }

    @Test
    void anInvalidObjectIdIsRefused() {
        Map<String, Object> where1 = Map.of("id", Map.of("eq", "not-an-id"));
        assertThatThrownBy(() -> compile(where1))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void aFieldThatIsNotDeclaredIsRefused() {
        Map<String, Object> where2 = Map.of("nope", Map.of("eq", "x"));
        assertThatThrownBy(() -> compile(where2))
                .isInstanceOf(RequestException.class).hasMessageContaining("nope");
    }

    @Test
    void aFieldTheCallerMayNotSeeIsRefusedLikeAnUnknownOne() {
        Map<String, Object> filter1 = Map.of("secret", Map.of("eq", "x"));
        assertThatThrownBy(() -> compiler.compile(ORDERS, Set.of("status"), filter1))
                .isInstanceOf(RequestException.class).hasMessageContaining("secret");
    }

    @Test
    void anOperatorTheTypeDoesNotSupportFailsLoudly() {
        Map<String, Object> where3 = Map.of("paid", Map.of("gt", true));
        assertThatThrownBy(() -> compile(where3))
                .isInstanceOf(RequestException.class).hasMessageContaining("gt");
    }

    @Test
    void anUnknownOperatorIsRefused() {
        Map<String, Object> where4 = Map.of("status", Map.of("$where", "sleep(1000)"));
        assertThatThrownBy(() -> compile(where4))
                .isInstanceOf(RequestException.class);
        Map<String, Object> where5 = Map.of("status", Map.of("$expr", "1"));
        assertThatThrownBy(() -> compile(where5))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void eqNullIsRefusedInFavourOfIsNull() {
        HashMap<String, Object> ops = new HashMap<>();
        ops.put("eq", null);
        Map<String, Object> where6 = Map.of("status", ops);
        assertThatThrownBy(() -> compile(where6))
                .isInstanceOf(RequestException.class).hasMessageContaining("isNull");
    }

    @Test
    void aValueThatIsAnObjectCanNeverCarryAnOperator() {
        Map<String, Object> where7 = Map.of("status", Map.of("eq", Map.of("$ne", "x")));
        assertThatThrownBy(() -> compile(where7))
                .isInstanceOf(RequestException.class);
        Map<String, Object> where8 = Map.of("status", Map.of("in", List.of(Map.of("$function", "x"))));
        assertThatThrownBy(() -> compile(where8))
                .isInstanceOf(RequestException.class);
        Map<String, Object> where9 = Map.of("total", Map.of("eq", "5"));
        assertThatThrownBy(() -> compile(where9))
                .isInstanceOf(RequestException.class);
    }

    @Test
    void operatorLookingStringsAreOnlyEverLiterals() {
        for (String hostile : List.of("$where", "$expr", "$function", "{\"$ne\": null}", "$$ROOT", "this.a == 1")) {
            Document doc = compile(Map.of("status", Map.of("eq", hostile))).doc();
            assertThat(doc).isEqualTo(new Document("status", Fixtures.scalar("$eq", hostile)));
            assertThat(compile(Map.of("status", Map.of("in", List.of(hostile)))).doc())
                    .isEqualTo(new Document("status", Fixtures.scalar("$in", List.of(hostile))));
        }
    }

    @Test
    void theResultNeverContainsAnOperatorTheCompilerDidNotWrite() {
        Set<String> allowed = Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$and", "$or", "$not", "$type");
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
