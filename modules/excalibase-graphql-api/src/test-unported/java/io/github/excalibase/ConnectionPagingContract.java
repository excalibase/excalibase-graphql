package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Walking a connection page by page returns every row exactly once, in the requested order, forwards
 * ({@code first}/{@code after}) and backwards ({@code last}/{@code before}), over a table with duplicate
 * and NULL sort values. Cursors are keyset positions (sort key and primary key), and {@code endCursor} is
 * the last edge returned. The same contract runs on Postgres and MySQL.
 */
abstract class ConnectionPagingContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** id, price, category of the fixture rows. */
    private record Row(int id, double price, String category) {}

    private static final List<Row> ROWS = List.of(new Row(1, 10, "x"), new Row(2, 5, null), new Row(3, 10, "y"),
            new Row(4, 7.5, "x"), new Row(5, 5, null), new Row(6, 10, null), new Row(7, 7.5, "y"));

    @Autowired
    private MockMvc mockMvc;

    /** The connection field of the fixture's products table. */
    abstract String connection();

    @ParameterizedTest
    @ValueSource(strings = {"", "{ id: ASC }", "{ id: DESC }", "{ price: DESC }", "{ price: ASC }",
            "{ category: ASC }", "{ category: DESC }", "{ price: DESC, category: ASC }"})
    void walkingForward_returnsEveryRowOnce_inTheRequestedOrder(String orderBy) throws Exception {
        List<Integer> seen = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 10; page++) {
            JsonNode connection = page(orderBy, "first: 2" + (after == null ? "" : ", after: \"" + after + "\""));
            JsonNode edges = connection.path("edges");
            edges.forEach(edge -> seen.add(edge.path("node").path("id").asInt()));
            if (!edges.isEmpty()) {
                assertThat(connection.path("pageInfo").path("endCursor").asText())
                        .isEqualTo(edges.get(edges.size() - 1).path("cursor").asText());
            }
            if (!connection.path("pageInfo").path("hasNextPage").asBoolean()) break;
            after = connection.path("pageInfo").path("endCursor").asText();
        }
        assertThat(seen).containsExactlyElementsOf(expected(orderBy));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{ price: DESC }", "{ category: ASC }", "{ category: DESC }"})
    void walkingBackward_returnsEveryRowOnce_inTheRequestedOrder(String orderBy) throws Exception {
        List<Integer> seen = new ArrayList<>();
        String before = null;
        for (int page = 0; page < 10; page++) {
            JsonNode connection = page(orderBy, "last: 2" + (before == null ? "" : ", before: \"" + before + "\""));
            List<Integer> ids = new ArrayList<>();
            connection.path("edges").forEach(edge -> ids.add(edge.path("node").path("id").asInt()));
            seen.addAll(0, ids);
            if (!connection.path("pageInfo").path("hasPreviousPage").asBoolean()) break;
            before = connection.path("pageInfo").path("startCursor").asText();
        }
        assertThat(seen).containsExactlyElementsOf(expected(orderBy));
    }

    @Test
    void aCursorOfTheEarlierFormat_stillPagesByPrimaryKey() throws Exception {
        String legacy = Base64.getEncoder().encodeToString("3".getBytes(StandardCharsets.UTF_8));
        JsonNode connection = page("", "first: 2, after: \"" + legacy + "\"");
        List<Integer> ids = new ArrayList<>();
        connection.path("edges").forEach(edge -> ids.add(edge.path("node").path("id").asInt()));
        assertThat(ids).containsExactly(4, 5);
    }

    @Test
    void aCursorThatIsNotOne_isRefusedWithAMessage() throws Exception {
        String body = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("query",
                                "{ " + connection() + "(first: 2, after: \"%%%\") { edges { cursor } } }"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(body).path("errors").get(0).path("message").asText()).contains("cursor");
    }

    private JsonNode page(String orderBy, String args) throws Exception {
        String query = "{ " + connection() + "(" + args + (orderBy.isEmpty() ? "" : ", orderBy: " + orderBy) + ") {"
                + " edges { cursor node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }";
        String body = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("query", query))))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = MAPPER.readTree(body);
        assertThat(response.path("errors").isMissingNode()).as(body).isTrue();
        return response.path("data").path(connection());
    }

    /** The fixture ordered as asked, NULLs last ascending and first descending, ties by id. */
    private static List<Integer> expected(String orderBy) {
        Comparator<Row> order = (left, right) -> 0;
        String spec = orderBy.replace("{", "").replace("}", "").trim();
        for (String part : spec.isEmpty() ? new String[0] : spec.split(",")) {
            String[] pair = part.split(":");
            String column = pair[0].trim();
            boolean desc = pair[1].trim().equals("DESC");
            Comparator<Row> key = switch (column) {
                case "id" -> Comparator.comparingInt(Row::id);
                case "price" -> Comparator.comparingDouble(Row::price);
                default -> Comparator.comparing(Row::category, Comparator.nullsLast(Comparator.naturalOrder()));
            };
            order = order.thenComparing(desc ? key.reversed() : key);
        }
        List<Row> rows = new ArrayList<>(ROWS);
        rows.sort(order.thenComparingInt(Row::id));
        return rows.stream().map(Row::id).toList();
    }

}
