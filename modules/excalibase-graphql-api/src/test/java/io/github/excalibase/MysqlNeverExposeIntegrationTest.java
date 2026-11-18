package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.support.InternalSchemaProbe.INTROSPECTION;
import static io.github.excalibase.support.InternalSchemaProbe.assertNothingInternal;
import static io.github.excalibase.support.InternalSchemaProbe.fieldsOfTypeWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The never-expose guard on MySQL (EXC-733): the engine's user may read the internal databases, and a table
 * keeps a foreign key into one of them, yet none of it is served, and the key does not bind to a table of
 * the served database that happens to share the name.
 */
class MysqlNeverExposeIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-never-mysql";
    private static final String DATABASE = "shop";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> FIXTURE = List.of(
            "CREATE DATABASE excalibase_meta", "CREATE DATABASE auth",
            "CREATE TABLE excalibase_meta.t (id INT PRIMARY KEY, secret VARCHAR(40))",
            "CREATE TABLE auth.users (id INT PRIMARY KEY, password_hash VARCHAR(40))",
            "INSERT INTO excalibase_meta.t VALUES (1, 'document-secret')",
            "INSERT INTO auth.users VALUES (1, 'hash-secret')",
            "CREATE TABLE shop.t (id INT PRIMARY KEY, label VARCHAR(40))",
            "INSERT INTO shop.t VALUES (1, 'plain')",
            "CREATE TABLE shop.items (id INT PRIMARY KEY, name VARCHAR(40), meta_id INT, user_id INT,"
                    + " FOREIGN KEY (meta_id) REFERENCES excalibase_meta.t (id),"
                    + " FOREIGN KEY (user_id) REFERENCES auth.users (id))",
            "INSERT INTO shop.items VALUES (1, 'visible', 1, 1)",
            "GRANT ALL ON *.* TO 'test'@'%'");

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName(DATABASE);

    @Override
    public Map<String, String> getProperties() {
        mysql.start();
        try {
            for (String statement : FIXTURE) {
                var result = mysql.execInContainer("mysql", "-uroot", "-p" + mysql.getPassword(), "-e", statement);
                assertThat(result.getExitCode()).as(statement + result.getStderr()).isZero();
            }
        } catch (IOException e) {
            throw new IllegalStateException("fixture failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", e);
        }
        return EngineProperties.mysql(mysql, PROJECT);
    }

    private HttpAnswer graphql(String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)),
                "Content-Type", "application/json");
    }

    @Test
    void introspection_namesNoInternalTable_andTheForeignKeyIntoOneIsNotARelationship() throws Exception {
        HttpAnswer answer = graphql(INTROSPECTION);

        assertThat(answer.body()).contains("ShopItems");
        assertNothingInternal(answer.body());
        assertThat(fieldsOfTypeWith(answer.json(), "meta_id")).containsExactlyInAnyOrder("id", "name", "meta_id", "user_id");
        assertThat(fieldsOfTypeWith(answer.json(), "label")).containsExactlyInAnyOrder("id", "label");
    }

    @Test
    void restOnAnInternalDatabase_answers404ForEveryMethod() throws Exception {
        for (String[] target : List.of(new String[] {"/t?id=eq.1", "excalibase_meta"},
                new String[] {"/users?id=eq.1", "auth"})) {
            for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
                HttpAnswer answer = http.send(method, "/" + PROJECT + "/api/v1" + target[0],
                        "GET".equals(method) || "DELETE".equals(method) ? null : "{\"id\": 9}",
                        "GET".equals(method) ? "Accept-Profile" : "Content-Profile", target[1],
                        "Content-Type", "application/json");
                assertThat(answer.status()).as(method + " " + target[1]).isEqualTo(404);
                assertNothingInternal(answer.body());
            }
        }
    }

    @Test
    void theOpenApiDocument_describesNoInternalPath() throws Exception {
        HttpAnswer answer = http.get("/" + PROJECT + "/api/v1", "Accept", "application/openapi+json");

        assertThat(answer.status()).isEqualTo(200);
        assertNothingInternal(answer.body());
    }
}
