package io.github.excalibase.controller

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification
import spock.lang.Shared

import java.sql.*

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import static org.hamcrest.Matchers.*

/**
 * Tests GraphQL identifier sanitization:
 *  - Enum values with hyphens (PG-13 → PG_13, NC-17 → NC_17)
 *  - Column names with spaces ("zip code" → zip_code)
 *
 * Covers: schema introspection, querying, filtering, and mutation with these identifiers.
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Testcontainers
class GraphqlIdentifierSanitizationTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")

    @Autowired
    WebApplicationContext webApplicationContext

    MockMvc mockMvc

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.datasource.hikari.schema", { "public" })
        registry.add("app.allowed-schema", { "public" })
        registry.add("app.database-type", { "postgres" })
    }

    def setupSpec() {
        postgres.start()
        setupTestData()
    }

    def cleanupSpec() {
        postgres.stop()
    }

    def setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private static void setupTestData() {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Enum with hyphens — same pattern as dvdrental mpaa_rating
            stmt.execute("DROP TYPE IF EXISTS mpaa_rating CASCADE")
            stmt.execute("CREATE TYPE mpaa_rating AS ENUM ('G', 'PG', 'PG-13', 'R', 'NC-17')")

            // Table using the enum (no tsvector — excluded from inputs)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS film_sample (
                    film_id SERIAL PRIMARY KEY,
                    title   VARCHAR(255) NOT NULL,
                    rating  mpaa_rating
                )
            """)

            // View with a space in a column name — same pattern as dvdrental staff_list
            stmt.execute("""
                CREATE OR REPLACE VIEW film_details AS
                    SELECT film_id,
                           title,
                           rating,
                           title AS "display title"
                    FROM film_sample
            """)

            stmt.execute("""
                INSERT INTO film_sample (title, rating) VALUES
                ('Chamber Italian',  'PG-13'),
                ('Grosse Wonderful', 'NC-17'),
                ('Airport Pollock',  'G')
                ON CONFLICT DO NOTHING
            """)

        } catch (Exception e) {
            System.err.println("Error setting up identifier sanitization test data: " + e.getMessage())
            e.printStackTrace()
        }
    }

    // ── Enum introspection ────────────────────────────────────────────────────

    def "enum type with hyphen values is exposed with underscores in GraphQL schema"() {
        given: "introspection query for MpaaRating enum"
        def query = '{ __type(name: \\"MpaaRating\\") { enumValues { name } } }'

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query}"}"""))

        then: "PG-13 and NC-17 are exposed as PG_13 and NC_17"
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', hasItem("PG_13")))
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', hasItem("NC_17")))
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', hasItem("G")))
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', hasItem("PG")))
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', hasItem("R")))
              // Raw names with hyphens must NOT appear
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', not(hasItem("PG-13"))))
              .andExpect(jsonPath('$.data.__type.enumValues[*].name', not(hasItem("NC-17"))))
    }

    // ── Column name with space — introspection ────────────────────────────────

    def "view column with space is exposed as underscore name in GraphQL schema"() {
        given: "introspection query for FilmDetails type"
        def query = '{ __type(name: \\"FilmDetails\\") { fields { name } } }'

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query}"}"""))

        then: '"display title" is exposed as display_title'
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.__type.fields[*].name', hasItem("display_title")))
              .andExpect(jsonPath('$.data.__type.fields[*].name', not(hasItem("display title"))))
    }

    // ── Query with enum value ─────────────────────────────────────────────────

    def "query returns enum values as sanitized names"() {
        given: "query for film_sample with rating field"
        def query = '{ filmSample { film_id title rating } }'

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query}"}"""))

        then: "rating values use underscore form"
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.filmSample[*].rating', hasItem("PG_13")))
              .andExpect(jsonPath('$.data.filmSample[*].rating', hasItem("NC_17")))
              .andExpect(jsonPath('$.data.filmSample[*].rating', hasItem("G")))
    }

    def "filter by sanitized enum value PG_13 returns matching rows"() {
        given: "query filtering by PG_13"
        def query = '{ filmSample(where: { rating: { eq: PG_13 } }) { title rating } }'

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query}"}"""))

        then: "only PG-13 film is returned"
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.filmSample', hasSize(1)))
              .andExpect(jsonPath('$.data.filmSample[0].title').value("Chamber Italian"))
              .andExpect(jsonPath('$.data.filmSample[0].rating').value("PG_13"))
    }

    // ── Query view with space column ──────────────────────────────────────────

    def "query view returns spaced column under its sanitized name"() {
        given: "query for film_details requesting display_title"
        def query = '{ filmDetails { film_id title display_title } }'

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query}"}"""))

        then: "display_title field is populated with the title value"
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.filmDetails', hasSize(3)))
              // display_title = title in the view — verify it returns a non-null string
              .andExpect(jsonPath('$.data.filmDetails[0].display_title').isString())
              .andExpect(jsonPath('$.data.filmDetails[1].display_title').isString())
              .andExpect(jsonPath('$.data.filmDetails[2].display_title').isString())
    }

    // ── Mutation with enum ────────────────────────────────────────────────────

    def "create film with PG_13 rating persists and returns sanitized enum value"() {
        given: "mutation creating a film with rating PG_13"
        def mutation = '''
        mutation {
            createFilmSample(input: {
                title: "Test Film PG13"
                rating: PG_13
            }) {
                film_id
                title
                rating
            }
        }
        '''

        when:
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${mutation.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "film is created and rating is returned as PG_13"
        result.andExpect(status().isOk())
              .andExpect(jsonPath('$.errors').doesNotExist())
              .andExpect(jsonPath('$.data.createFilmSample.title').value("Test Film PG13"))
              .andExpect(jsonPath('$.data.createFilmSample.rating').value("PG_13"))
              .andExpect(jsonPath('$.data.createFilmSample.film_id').exists())
    }
}
