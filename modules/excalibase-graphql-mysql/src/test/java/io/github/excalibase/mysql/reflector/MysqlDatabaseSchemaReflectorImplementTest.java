package io.github.excalibase.mysql.reflector;

import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MysqlDatabaseSchemaReflectorImplement} using a real MySQL container.
 */
@Testcontainers
class MysqlDatabaseSchemaReflectorImplementTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
    private MysqlDatabaseSchemaReflectorImplement reflector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(mysql.getJdbcUrl());
        ds.setUsername(mysql.getUsername());
        ds.setPassword(mysql.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(100) NOT NULL,
                    email VARCHAR(200),
                    active TINYINT(1) DEFAULT 1,
                    score DECIMAL(10,2),
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    body TEXT,
                    user_id BIGINT,
                    published_at TIMESTAMP,
                    CONSTRAINT fk_posts_users FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tags (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL,
                    color ENUM('red','green','blue') DEFAULT 'red'
                )
                """);

        reflector = new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "testdb");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS posts");
        jdbcTemplate.execute("DROP TABLE IF EXISTS users");
        jdbcTemplate.execute("DROP TABLE IF EXISTS tags");
    }

    @Test
    void shouldDiscoverAllTables() {
        Map<String, TableInfo> schema = reflector.reflectSchema();

        assertThat(schema).containsKeys("users", "posts", "tags")
                .hasSize(3);
    }

    @Test
    void shouldReflectColumnsForUsersTable() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");

        assertThat(users).isNotNull();
        List<String> columnNames = users.getColumns().stream()
                .map(ColumnInfo::getName).toList();
        assertThat(columnNames).containsExactlyInAnyOrder(
                "id", "username", "email", "active", "score", "created_at");
    }

    @Test
    void shouldMarkPrimaryKeyCorrectly() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");

        ColumnInfo idColumn = users.getColumns().stream()
                .filter(c -> "id".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(idColumn.isPrimaryKey()).isTrue();

        ColumnInfo usernameColumn = users.getColumns().stream()
                .filter(c -> "username".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(usernameColumn.isPrimaryKey()).isFalse();
    }

    @Test
    void shouldMarkNullableColumnsCorrectly() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");

        ColumnInfo emailColumn = users.getColumns().stream()
                .filter(c -> "email".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(emailColumn.isNullable()).isTrue();

        ColumnInfo usernameColumn = users.getColumns().stream()
                .filter(c -> "username".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(usernameColumn.isNullable()).isFalse();
    }

    @Test
    void shouldDetectForeignKey() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo posts = schema.get("posts");

        assertThat(posts.getForeignKeys()).hasSize(1);
        ForeignKeyInfo fk = posts.getForeignKeys().getFirst();
        assertThat(fk.getColumnName()).isEqualTo("user_id");
        assertThat(fk.getReferencedTable()).isEqualTo("users");
        assertThat(fk.getReferencedColumn()).isEqualTo("id");
    }

    @Test
    void shouldMapVarcharToString() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");

        ColumnInfo usernameColumn = users.getColumns().stream()
                .filter(c -> "username".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(usernameColumn.getType()).isEqualTo("varchar");
    }

    @Test
    void shouldMapBigintToLong() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");

        ColumnInfo idColumn = users.getColumns().stream()
                .filter(c -> "id".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(idColumn.getType()).isEqualTo("bigint");
    }

    @Test
    void shouldDetectEnumColumn() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo tags = schema.get("tags");

        ColumnInfo colorColumn = tags.getColumns().stream()
                .filter(c -> "color".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(colorColumn.getType()).isEqualTo("enum");
    }

    @Test
    void shouldReturnEmptyListForCustomEnumTypes() {
        // MySQL doesn't have schema-level ENUMs — they're inline column definitions
        assertThat(reflector.getCustomEnumTypes()).isEmpty();
        assertThat(reflector.getCustomEnumTypes("testdb")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForCustomCompositeTypes() {
        // MySQL doesn't support composite types
        assertThat(reflector.getCustomCompositeTypes()).isEmpty();
        assertThat(reflector.getCustomCompositeTypes("testdb")).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapForDomainTypes() {
        // MySQL doesn't support domain types
        assertThat(reflector.getDomainTypeToBaseTypeMap()).isEmpty();
        assertThat(reflector.getDomainTypeToBaseTypeMap("testdb")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForEnumValues() {
        // MySQL enum values are inline — no schema-level lookup
        assertThat(reflector.getEnumValues("color", "testdb")).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapForComputedFields() {
        // MySQL doesn't support computed fields via functions in same pattern as Postgres
        assertThat(reflector.discoverComputedFields()).isEmpty();
        assertThat(reflector.discoverComputedFields("testdb")).isEmpty();
    }

    @Test
    void shouldClearCacheAndReflectFreshSchema() {
        // First call populates cache
        Map<String, TableInfo> first = reflector.reflectSchema();
        assertThat(first).containsKey("users");

        // Clear and re-reflect should still work
        reflector.clearCache();
        Map<String, TableInfo> second = reflector.reflectSchema();
        assertThat(second).containsKey("users");
    }

    @Test
    void shouldHandleTableWithNoForeignKeys() {
        Map<String, TableInfo> schema = reflector.reflectSchema();
        TableInfo users = schema.get("users");
        assertThat(users.getForeignKeys()).isEmpty();
    }
}
