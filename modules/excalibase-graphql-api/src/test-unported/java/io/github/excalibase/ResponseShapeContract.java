package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The same rows read through REST and GraphQL answer the same JSON: every column present (null included),
 * booleans as true/false, timestamps in ISO 8601 with their zone, and fields in the order they were asked for.
 * Fixture: {@code items(id, name, note, active, qty, created_at, day)}, row 2 holding the nulls.
 */
abstract class ResponseShapeContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLUMNS = "id,name,note,active,qty,created_at,day";
    private static final String ISO_WITH_ZONE = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})";

    @Autowired
    private MockMvc mockMvc;

    /** The GraphQL query field of the items table. */
    abstract String itemsField();

    private JsonNode rest(String path) throws Exception {
        MvcResult result = mockMvc.perform(get("/test-proj/api/v1/" + path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode graphql(String query) throws Exception {
        MvcResult result = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query)))).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("errors")).as(body.toString()).isNull();
        return body.get("data");
    }

    private static List<String> keys(JsonNode row) {
        List<String> keys = new ArrayList<>();
        row.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    @DisplayName("REST and GraphQL answer the same values for the same rows")
    void sameRowsSameValues() throws Exception {
        JsonNode viaRest = rest("items?select=" + COLUMNS + "&order=id.asc");
        JsonNode viaGraphql = graphql("{ " + itemsField() + "(orderBy: { id: ASC }) { "
                + COLUMNS.replace(',', ' ') + " } }").get(itemsField());

        assertThat(viaRest).hasSize(2);
        assertThat(viaRest).isEqualTo(viaGraphql);
    }

    @Test
    @DisplayName("a NULL column is present as null in REST, as in GraphQL")
    void nullsArePresent() throws Exception {
        JsonNode milk = rest("items?select=" + COLUMNS + "&id=eq.2").get(0);

        assertThat(keys(milk)).containsExactly("id", "name", "note", "active", "qty", "created_at", "day");
        assertThat(milk.get("note").isNull()).isTrue();
        assertThat(milk.get("qty").isNull()).isTrue();
        assertThat(rest("items?id=eq.2").get(0).has("note")).isTrue();
    }

    @Test
    @DisplayName("booleans are true and false, timestamps ISO 8601 with their zone")
    void booleansAndTimestamps() throws Exception {
        JsonNode viaRest = rest("items?select=id,active,created_at&order=id.asc");

        assertThat(viaRest.get(0).get("active").isBoolean()).isTrue();
        assertThat(viaRest.get(0).get("active").asBoolean()).isTrue();
        assertThat(viaRest.get(1).get("active").asBoolean()).isFalse();
        assertThat(viaRest.get(0).get("created_at").asText()).matches(ISO_WITH_ZONE);
    }

    @Test
    @DisplayName("an aggregate's timestamp reads like the column does")
    void aggregateTimestampsAreIso() throws Exception {
        JsonNode max = graphql("{ " + itemsField() + "Aggregate { max { created_at } } }")
                .get(itemsField() + "Aggregate").get("max").get("created_at");
        JsonNode newest = rest("items?select=created_at&id=eq.1").get(0).get("created_at");

        assertThat(max.asText()).matches(ISO_WITH_ZONE).isEqualTo(newest.asText());
    }

    @Test
    @DisplayName("fields come back in the order they were selected")
    void selectionOrder() throws Exception {
        JsonNode viaGraphql = graphql("{ " + itemsField() + "(orderBy: { id: ASC }) { note created_at id name } }")
                .get(itemsField()).get(0);
        JsonNode viaRest = rest("items?select=note,created_at,id,name&order=id.asc").get(0);

        assertThat(keys(viaGraphql)).containsExactly("note", "created_at", "id", "name");
        assertThat(keys(viaRest)).containsExactly("note", "created_at", "id", "name");
    }
}
