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
 * PostgREST's filter and paging semantics, held by the REST API on every database it serves, and the
 * GraphQL text filters that mean the same. Fixture: {@code goods(id, sku unique, name, cost nullable, kind enum)}
 * with five rows, two of them without a cost and two whose names hold {@code %} and {@code _}.
 */
abstract class RestFilterSemanticsContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";

    @Autowired
    private MockMvc mockMvc;

    /** The GraphQL query field of the goods table. */
    abstract String goodsField();

    private MvcResult call(String path) throws Exception {
        return mockMvc.perform(get(REST + path)).andReturn();
    }

    private List<String> skus(String query) throws Exception {
        MvcResult result = call("goods?select=sku&order=sku.asc&" + query);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return skusOf(MAPPER.readTree(result.getResponse().getContentAsString()).get("data"));
    }

    private static List<String> skusOf(JsonNode rows) {
        List<String> skus = new ArrayList<>();
        rows.forEach(row -> skus.add(row.get("sku").asText()));
        return skus;
    }

    private void assertBadRequest(String path, String... notLeaked) throws Exception {
        MvcResult result = call(path);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
        for (String leaked : notLeaked) {
            assertThat(body).doesNotContain(leaked);
        }
    }

    private List<String> graphqlSkus(String where) throws Exception {
        String query = "{ " + goodsField() + "(orderBy: { sku: ASC }, where: { " + where + " }) { sku } }";
        MvcResult result = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query)))).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("errors")).as(body.toString()).isNull();
        return skusOf(body.get("data").get(goodsField()));
    }

    @Test
    @DisplayName("is.null, is.notnull and is.not_null split the rows by a missing value")
    void isNullAndNotNull() throws Exception {
        assertThat(skus("cost=is.null")).containsExactly("B-2", "D-4");
        assertThat(skus("cost=is.notnull")).containsExactly("A-1", "C-3", "E-5");
        assertThat(skus("cost=is.not_null")).containsExactly("A-1", "C-3", "E-5");
        assertThat(skus("cost=not.is.null")).containsExactly("A-1", "C-3", "E-5");
    }

    @Test
    @DisplayName("is with a value it does not know is refused, not read as is.null")
    void isUnknownValue_isRefused() throws Exception {
        assertBadRequest("goods?cost=is.nothing");
    }

    @Test
    @DisplayName("startswith and endswith match % and _ as themselves")
    void restAffixesAreLiteral() throws Exception {
        assertThat(skus("name=startswith.100%")).containsExactly("B-2");
        assertThat(skus("name=startswith.%")).isEmpty();
        assertThat(skus("name=endswith._case")).containsExactly("C-3");
        assertThat(skus("name=endswith._")).isEmpty();
    }

    @Test
    @DisplayName("GraphQL contains, startsWith and endsWith match % and _ as themselves")
    void graphqlTextFiltersAreLiteral() throws Exception {
        assertThat(graphqlSkus("name: { contains: \"%\" }")).containsExactly("B-2");
        assertThat(graphqlSkus("name: { contains: \"_\" }")).containsExactly("C-3");
        assertThat(graphqlSkus("name: { startsWith: \"%\" }")).isEmpty();
        assertThat(graphqlSkus("name: { endsWith: \"_\" }")).isEmpty();
    }

    @Test
    @DisplayName("like keeps PostgREST's pattern: * and % are wildcards")
    void likeIsAPattern() throws Exception {
        assertThat(skus("name=like.*Juice")).containsExactly("B-2");
        assertThat(skus("name=like.Br%")).containsExactly("D-4");
    }

    @Test
    @DisplayName("or nests and(...), or(...) and not.and(...) groups")
    void nestedLogic() throws Exception {
        assertThat(skus("or=(sku.eq.A-1,and(kind.eq.drink,cost.is.null))")).containsExactly("A-1", "B-2");
        assertThat(skus("or=(sku.eq.A-1,and(kind.eq.food,or(sku.eq.C-3,sku.eq.D-4)))"))
                .containsExactly("A-1", "C-3", "D-4");
        assertThat(skus("or=(sku.eq.E-5,not.and(kind.eq.food,cost.is.null))"))
                .containsExactly("A-1", "B-2", "C-3", "E-5");
        assertThat(skus("and=(kind.eq.food,cost.not.is.null)")).containsExactly("A-1", "C-3");
    }

    @Test
    @DisplayName("limit=0 returns no rows")
    void limitZero_returnsNoRows() throws Exception {
        assertThat(skus("limit=0")).isEmpty();
    }

    @Test
    @DisplayName("a negative or malformed limit or offset is refused without the parser's message")
    void badPaging_isRefused() throws Exception {
        assertBadRequest("goods?limit=-1");
        assertBadRequest("goods?offset=-5");
        assertBadRequest("goods?limit=abc", "For input string");
        assertBadRequest("goods?offset=abc", "For input string");
    }

    @Test
    @DisplayName("a value the enum column does not have is refused")
    void unknownEnumValue_isRefused() throws Exception {
        assertBadRequest("goods?kind=eq.toys");
        assertBadRequest("goods?kind=in.(food,toys)");
    }

    @Test
    @DisplayName("on_conflict merges on the named unique column instead of the primary key")
    void onConflict_mergesOnTheNamedColumn() throws Exception {
        MvcResult result = mockMvc.perform(post(REST + "goods?on_conflict=sku")
                .header("Prefer", "resolution=merge-duplicates,return=representation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"E-5\",\"name\":\"Cola Zero\",\"cost\":2.5,\"kind\":\"drink\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isLessThan(300);
        MvcResult read = call("goods?select=sku,name&sku=eq.E-5");
        JsonNode rows = MAPPER.readTree(read.getResponse().getContentAsString()).get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("Cola Zero");
        assertThat(skus("")).hasSize(5);
    }
}
