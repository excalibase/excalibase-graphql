package io.github.excalibase.mongodb;

import io.github.excalibase.spi.QueryEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads through GraphQL against a real database: list, get by id, filters, ordering, paging, and what is refused. */
abstract class EngineReadTest extends AbstractEngineTest {

    private Map<String, Object> run(QueryEngine engine, String query) {
        return engine.execute(query, Map.of(), caller("anyone"));
    }

    @Test
    void listsDeclaredCollectionWithItsFieldsAndTheIdAsText() {
        Map<String, Object> response = run(openEngine(), "{ orders(orderBy: [{field: id}]) { id owner total paid placedAt } }");

        assertThat(error(response)).isNull();
        List<Map<String, Object>> rows = rows(response, "orders");
        assertThat(rows).hasSize(5);
        assertThat(rows.getFirst()).containsEntry("id", ID_1).containsEntry("owner", "u1")
                .containsEntry("total", 10).containsEntry("paid", true)
                .containsEntry("placedAt", "2023-11-14T22:13:20Z");
        assertThat(rows.getLast()).containsEntry("owner", null).containsEntry("paid", null);
    }

    @Test
    void getsOneByIdAndNullWhenThereIsNone() {
        QueryEngine engine = openEngine();

        Map<String, Object> found = run(engine, "{ ordersById(id: \"" + ID_3 + "\") { id owner } }");
        Map<String, Object> missing = run(engine, "{ ordersById(id: \"65f0c0ffee0000000000ffff\") { id } }");

        assertThat(error(found)).isNull();
        assertThat(one(found, "ordersById")).containsEntry("owner", "u2");
        assertThat(one(missing, "ordersById")).isNull();
    }

    @Test
    void aMalformedIdIsRefusedNotTreatedAsAWildcard() {
        Map<String, Object> response = run(openEngine(), "{ ordersById(id: \"{\\\"$ne\\\": null}\") { id } }");

        assertThat(error(response)).contains("id");
    }

    @Test
    void filtersWithEqNeqRangesInAndIsNull() {
        QueryEngine engine = openEngine();

        assertThat(rows(run(engine, "{ orders(where: {status: {eq: \"open\"}}) { id } }"), "orders")).hasSize(2);
        assertThat(rows(run(engine, "{ orders(where: {status: {neq: \"open\"}}) { id } }"), "orders")).hasSize(2);
        assertThat(rows(run(engine, "{ orders(where: {total: {gt: 20, lt: 50}}) { total } }"), "orders"))
                .extracting(r -> r.get("total")).containsExactlyInAnyOrder(30, 40);
        assertThat(rows(run(engine, "{ orders(where: {owner: {in: [\"u1\", \"zz\"]}}) { id } }"), "orders")).hasSize(2);
        assertThat(rows(run(engine, "{ orders(where: {status: {isNull: true}}) { id } }"), "orders")).hasSize(1);
        assertThat(rows(run(engine, "{ orders(where: {status: {isNull: false}}) { id } }"), "orders")).hasSize(4);
        assertThat(rows(run(engine, "{ orders(where: {id: {in: [\"" + ID_1 + "\"]}}) { id } }"), "orders")).hasSize(1);
    }

    @Test
    void ordersAndPagesStably() {
        QueryEngine engine = openEngine();

        List<Map<String, Object>> page = rows(run(engine,
                "{ orders(orderBy: [{field: total, direction: DESC}], limit: 2, offset: 1) { total } }"), "orders");

        assertThat(page).extracting(r -> r.get("total")).containsExactly(40, 30);
    }

    @Test
    void refusesALimitBeyondTheCap() {
        assertThat(error(run(openEngine(), "{ orders(limit: 31) { id } }"))).contains("limit");
    }

    @Test
    void anOperatorTheTypeDoesNotHaveIsNotInTheSchema() {
        Map<String, Object> response = run(openEngine(), "{ orders(where: {paid: {gt: true}}) { id } }");

        assertThat(error(response)).isNotNull();
    }

    @Test
    void operatorShapedValuesAreOnlyEverLiterals() {
        QueryEngine engine = openEngine();
        for (String hostile : List.of("$where", "$expr", "$function", "{\\\"$ne\\\": null}", "this.a == 1", "$$ROOT")) {
            Map<String, Object> response = run(engine, "{ orders(where: {status: {eq: \"" + hostile + "\"}}) { id } }");
            assertThat(error(response)).as(hostile).isNull();
            assertThat(rows(response, "orders")).as(hostile).hasSize("$where".equals(hostile) ? 1 : 0);
        }
    }

    @Test
    void anObjectWhereAScalarBelongsNeverReachesTheDatabase() {
        QueryEngine engine = openEngine();

        Map<String, Object> viaVariable = engine.execute("query($v: String) { orders(where: {status: {eq: $v}}) { id } }",
                Map.of("v", Map.of("$ne", "x")), caller("anyone"));
        Map<String, Object> inline = run(engine, "{ orders(where: {status: {eq: {_: 1}}}) { id } }");
        Map<String, Object> unknownOperator = run(engine, "{ orders(where: {status: {where: \"1\"}}) { id } }");
        Map<String, Object> unknownField = run(engine, "{ orders(where: {\"$where\": {eq: \"1\"}}) { id } }");

        for (Map<String, Object> response : List.of(viaVariable, inline, unknownOperator, unknownField)) {
            assertThat(error(response)).isNotNull();
            assertThat(response.get("data")).isNull();
        }
    }
}
