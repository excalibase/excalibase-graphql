package io.github.excalibase.mysql.generator;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.StoredProcedureInfo;
import io.github.excalibase.model.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MysqlGraphQLSchemaGeneratorImplement}.
 */
class MysqlGraphQLSchemaGeneratorImplementTest {

    private MysqlGraphQLSchemaGeneratorImplement generator;

    @BeforeEach
    void setUp() {
        generator = new MysqlGraphQLSchemaGeneratorImplement();
    }

    private TableInfo buildTable(String name, ColumnInfo... columns) {
        TableInfo t = new TableInfo();
        t.setName(name);
        t.setColumns(new ArrayList<>(List.of(columns)));
        t.setForeignKeys(new ArrayList<>());
        return t;
    }

    private ColumnInfo col(String name, String type, boolean pk, boolean nullable) {
        ColumnInfo c = new ColumnInfo();
        c.setName(name);
        c.setType(type);
        c.setPrimaryKey(pk);
        c.setNullable(nullable);
        return c;
    }

    @Test
    void shouldGenerateSchemaForSimpleTable() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("username", "varchar", false, false),
                col("email", "varchar", false, true)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        assertThat(schema).isNotNull();
        GraphQLObjectType queryType = schema.getQueryType();
        assertThat(queryType.getFieldDefinition("users")).isNotNull();
        assertThat(queryType.getFieldDefinition("usersConnection")).isNotNull();
    }

    @Test
    void shouldGenerateMutationsForTable() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("username", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType mutationType = schema.getMutationType();
        assertThat(mutationType).isNotNull();
        assertThat(mutationType.getFieldDefinition("createUsers")).isNotNull();
        assertThat(mutationType.getFieldDefinition("updateUsers")).isNotNull();
        assertThat(mutationType.getFieldDefinition("deleteUsers")).isNotNull();
    }

    @Test
    void shouldMapBigintToIntType() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("items", buildTable("items",
                col("id", "bigint", true, false),
                col("quantity", "int", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType itemsType = (GraphQLObjectType) schema.getType("Items");
        assertThat(itemsType).isNotNull();
        GraphQLFieldDefinition idField = itemsType.getFieldDefinition("id");
        assertThat(idField).isNotNull();
        // bigint maps to Int in GraphQL
        assertThat(idField.getType().toString()).contains("Int");
    }

    @Test
    void shouldMapVarcharToStringType() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("items", buildTable("items",
                col("id", "int", true, false),
                col("name", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType itemsType = (GraphQLObjectType) schema.getType("Items");
        GraphQLFieldDefinition nameField = itemsType.getFieldDefinition("name");
        assertThat(nameField.getType().toString()).contains("String");
    }

    @Test
    void shouldGenerateAggregateQueryField() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("orders", buildTable("orders",
                col("id", "bigint", true, false),
                col("amount", "decimal", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType queryType = schema.getQueryType();
        assertThat(queryType.getFieldDefinition("ordersAggregate")).isNotNull();
    }

    @Test
    void shouldGenerateBulkCreateMutation() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("name", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType mutationType = schema.getMutationType();
        assertThat(mutationType.getFieldDefinition("createManyUsers")).isNotNull();
    }

    @Test
    void shouldGenerateCreateWithRelationsMutation() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("name", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType mutationType = schema.getMutationType();
        assertThat(mutationType.getFieldDefinition("createUsersWithRelations")).isNotNull();
    }

    @Test
    void shouldHandleMultipleTables() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("name", "varchar", false, false)));
        tables.put("posts", buildTable("posts",
                col("id", "bigint", true, false),
                col("title", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of());

        GraphQLObjectType queryType = schema.getQueryType();
        assertThat(queryType.getFieldDefinition("users")).isNotNull();
        assertThat(queryType.getFieldDefinition("posts")).isNotNull();
    }

    @Test
    void shouldGenerateProcedureMutationInSchema() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("orders", buildTable("orders",
                col("order_id", "bigint", true, false),
                col("customer_id", "bigint", false, false)));

        StoredProcedureInfo.ProcedureParam inParam =
                new StoredProcedureInfo.ProcedureParam("p_customer_id", "bigint", "IN", 1);
        StoredProcedureInfo.ProcedureParam outParam =
                new StoredProcedureInfo.ProcedureParam("p_count", "int", "OUT", 2);
        List<StoredProcedureInfo> procedures = List.of(
                new StoredProcedureInfo("get_customer_order_count", "testdb",
                        List.of(inParam, outParam))
        );

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of(), procedures);

        GraphQLObjectType mutationType = schema.getMutationType();
        assertThat(mutationType).isNotNull();
        assertThat(mutationType.getFieldDefinition("callGetCustomerOrderCount")).isNotNull();
    }

    @Test
    void shouldGenerateProcedureMutationWithInParamsAsArguments() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("orders", buildTable("orders",
                col("order_id", "bigint", true, false),
                col("total", "decimal", false, true)));

        StoredProcedureInfo.ProcedureParam inParam =
                new StoredProcedureInfo.ProcedureParam("p_customer_id", "bigint", "IN", 1);
        List<StoredProcedureInfo> procedures = List.of(
                new StoredProcedureInfo("get_customer_order_count", "testdb", List.of(inParam))
        );

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of(), procedures);

        GraphQLFieldDefinition procField = schema.getMutationType()
                .getFieldDefinition("callGetCustomerOrderCount");
        assertThat(procField).isNotNull();
        assertThat(procField.getArgument("p_customer_id")).isNotNull();
    }

    @Test
    void shouldHandleNoProcedures() {
        Map<String, TableInfo> tables = new HashMap<>();
        tables.put("users", buildTable("users",
                col("id", "bigint", true, false),
                col("name", "varchar", false, false)));

        GraphQLSchema schema = generator.generateSchema(tables, List.of(), List.of(), List.of());

        GraphQLObjectType mutationType = schema.getMutationType();
        assertThat(mutationType).isNotNull();
        // No callXxx mutation fields
        assertThat(mutationType.getFieldDefinitions().stream()
                .noneMatch(f -> f.getName().startsWith("call"))).isTrue();
    }
}
