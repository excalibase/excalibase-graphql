package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * List arguments as a client sends them: orderBy given whole or in part through variables orders the rows,
 * an orderBy list orders by each element in turn, an empty {@code in} matches nothing (and {@code notIn} everything),
 * and a negative limit, offset, first or last is refused as a request error, never an internal one.
 */
abstract class QueryArgumentsContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    /** The products list field. */
    abstract String field();

    @Test
    void orderBy_asAWholeVariable_ordersTheRows() throws Exception {
        assertThat(ids("query ($ob: ProductsOrderBy) { " + field() + "(orderBy: $ob) { id } }",
                Map.of("ob", Map.of("id", "DESC")))).containsExactly(5, 4, 3, 2, 1);
    }

    @Test
    void orderBy_directionsFromVariables_orderTheRows() throws Exception {
        assertThat(ids("query ($c: OrderDirection, $d: OrderDirection) { " + field()
                + "(orderBy: { category: $c, id: $d }) { id } }", Map.of("c", "DESC", "d", "DESC")))
                .containsExactly(4, 2, 5, 3, 1);
    }

    @Test
    void orderBy_unsetVariable_leavesTheDefaultOrder() throws Exception {
        assertThat(ids("query ($ob: ProductsOrderBy) { " + field() + "(orderBy: $ob) { id } }", Map.of()))
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void orderBy_asAList_ordersByEachElementInTurn() throws Exception {
        assertThat(ids("{ " + field() + "(orderBy: [{ category: DESC }, { id: ASC }]) { id } }", Map.of()))
                .containsExactly(2, 4, 1, 3, 5);
        assertThat(ids("query ($ob: [ProductsOrderBy]) { " + field() + "(orderBy: $ob) { id } }",
                Map.of("ob", List.of(Map.of("category", "ASC"), Map.of("id", "DESC")))))
                .containsExactly(5, 3, 1, 4, 2);
    }

    @Test
    void orderBy_notAnObject_isRefusedWithAMessage() throws Exception {
        assertThat(error("{ " + field() + "(orderBy: \"id\") { id } }")).contains("orderBy");
    }

    @Test
    void in_emptyList_matchesNoRow_andNotIn_emptyList_matchesEveryRow() throws Exception {
        assertThat(ids("{ " + field() + "(where: { id: { in: [] } }) { id } }", Map.of())).isEmpty();
        assertThat(ids("query ($ids: [Int]) { " + field() + "(where: { id: { in: $ids } }) { id } }",
                Map.of("ids", List.of()))).isEmpty();
        assertThat(ids("{ " + field() + "(where: { id: { notIn: [] } }) { id } }", Map.of())).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit: -1", "offset: -5", "limit: 2, offset: -1"})
    void negativeLimitOrOffset_isARequestError(String args) throws Exception {
        assertThat(error("{ " + field() + "(" + args + ") { id } }")).containsAnyOf("limit", "offset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"first: -1", "last: -2"})
    void negativeFirstOrLast_isARequestError(String args) throws Exception {
        assertThat(error("{ " + field() + "Connection(" + args + ") { edges { node { id } } } }"))
                .containsAnyOf("first", "last");
    }

    @Test
    void negativeLimit_fromAVariable_isARequestError() throws Exception {
        String body = send("query ($n: Int) { " + field() + "(limit: $n) { id } }", Map.of("n", -1));
        assertThat(MAPPER.readTree(body).path("errors").get(0).path("message").asText()).contains("limit");
    }

    List<Integer> ids(String query, Map<String, Object> variables) throws Exception {
        String body = send(query, variables);
        JsonNode response = MAPPER.readTree(body);
        assertThat(response.path("errors").isMissingNode()).as(body).isTrue();
        List<Integer> ids = new ArrayList<>();
        response.path("data").path(field()).forEach(row -> ids.add(row.path("id").asInt()));
        return ids;
    }

    String error(String query) throws Exception {
        JsonNode response = MAPPER.readTree(send(query, Map.of()));
        JsonNode error = response.path("errors").get(0);
        assertThat(error).as(response.toString()).isNotNull();
        assertThat(error.path("extensions").path("code").asText()).isNotEqualTo("internal_error");
        return error.path("message").asText();
    }

    private String send(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        return mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
