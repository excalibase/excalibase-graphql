package io.github.excalibase;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Column values round-trip exactly through REST and GraphQL: decimals keep every digit, the largest key the
 * column holds reads and filters back, binary reads as {@code \x} hex like Postgres bytea, and a JSON column
 * takes the same JSON value as input that it answers. Fixture: {@code ledger(id, amount numeric(30,10),
 * big_id, blob_col binary, meta json)} with row 1 at the extremes.
 */
abstract class ColumnTypesContract {

    /** Reads numbers as the server wrote them, every digit kept. */
    static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final String EXACT = "12345678901234567890.1234567891";

    @Autowired
    MockMvc mockMvc;

    /** The GraphQL query field of the ledger table, e.g. {@code publicLedger}. */
    abstract String ledgerField();

    /** The largest value {@code big_id} holds. */
    abstract String maxBigId();

    String mutationSuffix() {
        return Character.toUpperCase(ledgerField().charAt(0)) + ledgerField().substring(1);
    }

    JsonNode rest(String path) throws Exception {
        MvcResult result = mockMvc.perform(get("/test-proj/api/v1/" + path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }

    JsonNode restPost(String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/test-proj/api/v1/" + path)
                .header("Prefer", "return=representation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isLessThan(300);
        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
        return data.isArray() ? data.get(0) : data;
    }

    JsonNode graphql(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", query);
        request.put("variables", variables);
        MvcResult result = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request))).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("errors")).as(body.toString()).isNull();
        return body.get("data");
    }

    JsonNode graphqlRow(int id, String fields) throws Exception {
        return graphql("{ " + ledgerField() + "(where: { id: { eq: " + id + " } }) { " + fields + " } }", Map.of())
                .get(ledgerField()).get(0);
    }

    /** Decimals and bigints are answered as strings holding every digit, so no client parser can round them. */
    private static void assertExact(JsonNode amount) {
        assertThat(amount.isTextual()).as(amount.toString()).isTrue();
        assertThat(amount.asText()).isEqualTo(EXACT);
    }

    @Test
    @DisplayName("a decimal reads back as a string with every digit, through REST and GraphQL")
    void decimalsReadExactly() throws Exception {
        assertExact(rest("ledger?select=amount&id=eq.1").get(0).get("amount"));
        assertExact(rest("ledger?id=eq.1").get(0).get("amount"));
        assertExact(graphqlRow(1, "amount").get("amount"));
    }

    @Test
    @DisplayName("a decimal filter takes the value as a number or a string")
    void decimalsFilterExactly() throws Exception {
        assertThat(rest("ledger?select=id&amount=eq." + EXACT).findValuesAsText("id")).contains("1");
        assertThat(graphql("{ " + ledgerField() + "(where: { amount: { eq: " + EXACT + " } }) { id } }", Map.of())
                .get(ledgerField()).findValuesAsText("id")).contains("1");
        assertThat(graphql("{ " + ledgerField() + "(where: { amount: { eq: \"" + EXACT + "\" } }) { id } }", Map.of())
                .get(ledgerField()).findValuesAsText("id")).contains("1");
        assertThat(rest("ledger?select=id&amount=eq.12345678901234567890.1234567890")).isEmpty();
    }

    @Test
    @DisplayName("a decimal is written exactly from a number or a string, through REST and GraphQL")
    void decimalsWriteExactly() throws Exception {
        assertExact(restPost("ledger", "{\"id\": 20, \"amount\": " + EXACT + "}").get("amount"));
        assertExact(restPost("ledger", "{\"id\": 22, \"amount\": \"" + EXACT + "\"}").get("amount"));

        JsonNode created = graphql("mutation { create" + mutationSuffix() + "(input: { id: 21, amount: " + EXACT
                + " }) { amount } }", Map.of()).get("create" + mutationSuffix());
        assertExact(created.isArray() ? created.get(0).get("amount") : created.get("amount"));
        graphql("mutation ($a: BigDecimal) { create" + mutationSuffix() + "(input: { id: 23, amount: $a }) { id } }",
                Map.of("a", EXACT));
        assertExact(rest("ledger?select=amount&id=eq.21").get(0).get("amount"));
        assertExact(rest("ledger?select=amount&id=eq.23").get(0).get("amount"));
    }

    @Test
    @DisplayName("the largest bigint reads as a string, and filters and writes back from a number or a string")
    void largestKeyRoundTrips() throws Exception {
        assertThat(rest("ledger?select=big_id&id=eq.1").get(0).get("big_id").isTextual()).isTrue();
        assertThat(rest("ledger?select=big_id&id=eq.1").get(0).get("big_id").asText()).isEqualTo(maxBigId());
        assertThat(graphqlRow(1, "big_id").get("big_id").asText()).isEqualTo(maxBigId());
        assertThat(graphqlRow(1, "big_id").get("big_id").isTextual()).isTrue();
        assertThat(rest("ledger?select=id&big_id=eq." + maxBigId())).hasSize(1);
        assertThat(graphql("{ " + ledgerField() + "(where: { big_id: { eq: " + maxBigId() + " } }) { id } }",
                Map.of()).get(ledgerField())).hasSize(1);

        restPost("ledger", "{\"id\": 30, \"big_id\": " + maxBigId() + "}");
        graphql("mutation ($big: BigInteger) { create" + mutationSuffix() + "(input: { id: 31, big_id: $big }) { id } }",
                Map.of("big", maxBigId()));
        restPost("ledger", "{\"id\": 32, \"big_id\": \"" + maxBigId() + "\"}");
        assertThat(rest("ledger?select=id&big_id=eq." + maxBigId() + "&order=id.asc").findValuesAsText("id"))
                .containsExactly("1", "30", "31", "32");
    }

    @Test
    @DisplayName("aggregates over decimals and bigints are strings too")
    void aggregatesAreStrings() throws Exception {
        JsonNode aggregate = graphql("{ " + ledgerField() + "Aggregate(where: { id: { eq: 1 } }) { sum { amount } "
                + "max { amount big_id } } }", Map.of()).get(ledgerField() + "Aggregate");

        assertThat(aggregate.get("sum").get("amount").isTextual()).as(aggregate.toString()).isTrue();
        assertThat(new BigDecimal(aggregate.get("sum").get("amount").asText())).isEqualByComparingTo(EXACT);
        assertThat(aggregate.get("max").get("amount").asText()).isEqualTo(EXACT);
        assertThat(aggregate.get("max").get("big_id").asText()).isEqualTo(maxBigId());
    }

    @Test
    @DisplayName("the schema names the string scalars: BigDecimal for decimals, BigInteger for bigints")
    void schemaNamesTheScalars() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        graphql("{ __type(name: \"" + mutationSuffix() + "\") { fields { name type { name } } } }", Map.of())
                .get("__type").get("fields")
                .forEach(field -> fields.put(field.get("name").asText(), field.get("type").get("name").asText()));
        assertThat(fields).containsEntry("amount", "BigDecimal").containsEntry("big_id", "BigInteger");

        Map<String, String> filters = new LinkedHashMap<>();
        graphql("{ __type(name: \"" + mutationSuffix() + "WhereInput\") { inputFields { name type { name } } } }",
                Map.of()).get("__type").get("inputFields")
                .forEach(field -> filters.put(field.get("name").asText(), field.get("type").get("name").asText()));
        assertThat(filters).containsEntry("amount", "BigDecimalFilterInput").containsEntry("big_id", "BigIntegerFilterInput");
    }

    @Test
    @DisplayName("binary reads as \\x hex and is written from it")
    void binaryIsHex() throws Exception {
        assertThat(rest("ledger?select=blob_col&id=eq.1").get(0).get("blob_col").asText()).isEqualTo("\\x00ff10");
        assertThat(graphqlRow(1, "blob_col").get("blob_col").asText()).isEqualTo("\\x00ff10");

        restPost("ledger", "{\"id\": 40, \"blob_col\": \"\\\\x0a0b\"}");
        assertThat(rest("ledger?select=blob_col&id=eq.40").get(0).get("blob_col").asText()).isEqualTo("\\x0a0b");
    }

    @Test
    @DisplayName("a JSON column takes a JSON object as input, as it answers one")
    void jsonInputMatchesOutput() throws Exception {
        graphql("mutation ($meta: JSON) { create" + mutationSuffix() + "(input: { id: 50, meta: $meta }) { id } }",
                Map.of("meta", Map.of("tags", List.of("x", "y"), "n", 2)));

        assertThat(graphqlRow(50, "meta").get("meta")).isEqualTo(MAPPER.readTree("{\"tags\":[\"x\",\"y\"],\"n\":2}"));
        JsonNode inputFields = graphql("{ __type(name: \"" + mutationSuffix() + "CreateInput\") { inputFields { name type { name } } } }",
                Map.of()).get("__type").get("inputFields");
        List<String> metaTypes = new ArrayList<>();
        inputFields.forEach(field -> {
            if ("meta".equals(field.get("name").asText())) metaTypes.add(field.get("type").get("name").asText());
        });
        assertThat(metaTypes).containsExactly("JSON");
    }

}
