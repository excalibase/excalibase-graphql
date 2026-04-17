package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the application starts gracefully when:
 * 1. The database is unreachable (bad URL)
 * 2. GraphQL requests return meaningful errors instead of 500s
 * 3. Introspection returns a valid (empty) schema
 */
@SpringBootTest
@AutoConfigureMockMvc
class GracefulStartupTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Point to a non-existent database — app must still start
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");

        registry.add("app.database-type", () -> "postgres");
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    @Test
    void appStartsWithoutDatabase() {
        // If we get here, @SpringBootTest context loaded successfully — app started
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void graphqlEndpoint_returnsError_notServerCrash() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ users { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void introspection_returnsValidSchema_whenNoDatabase() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { queryType { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"));
    }

    @Test
    void introspection_noMutationType_whenNoTables() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { mutationType } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.mutationType").doesNotExist());
    }

    @Test
    void missingQuery_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("query", ""))))
                .andExpect(status().isBadRequest());
    }
}
