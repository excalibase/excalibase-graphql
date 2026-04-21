package io.github.excalibase;

import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import io.github.excalibase.schema.IntrospectionHandler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for graphql-java Validator against IntrospectionHandler schema.
 * No Spring context — builds SchemaInfo manually.
 * Goal: document what the validator accepts/rejects before wiring into the controller.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphQLValidatorTest {

    private GraphQLSchema schema;
    private Validator validator;

    @BeforeEach
    void setUp() {
        SchemaInfo schemaInfo = new SchemaInfo();

        // customer table (addColumn implicitly registers the table)
        schemaInfo.addColumn("test_schema.customer", "customer_id", "integer");
        schemaInfo.addColumn("test_schema.customer", "first_name", "varchar");
        schemaInfo.addColumn("test_schema.customer", "last_name", "varchar");
        schemaInfo.addColumn("test_schema.customer", "email", "varchar");
        schemaInfo.addColumn("test_schema.customer", "active", "boolean");
        schemaInfo.addPrimaryKey("test_schema.customer", "customer_id");

        // orders table with FK to customer
        schemaInfo.addColumn("test_schema.orders", "order_id", "integer");
        schemaInfo.addColumn("test_schema.orders", "customer_id", "integer");
        schemaInfo.addColumn("test_schema.orders", "total_amount", "numeric");
        schemaInfo.addPrimaryKey("test_schema.orders", "order_id");
        schemaInfo.addForeignKey("test_schema.orders", "customer_id", "test_schema.customer", "customer_id");

        // order_items table with FK to orders
        schemaInfo.addColumn("test_schema.order_items", "order_id", "integer");
        schemaInfo.addColumn("test_schema.order_items", "product_id", "integer");
        schemaInfo.addColumn("test_schema.order_items", "quantity", "integer");
        schemaInfo.addColumn("test_schema.order_items", "price", "numeric");
        schemaInfo.addForeignKey("test_schema.order_items", "order_id", "test_schema.orders", "order_id");

        IntrospectionHandler handler = new IntrospectionHandler(schemaInfo);
        schema = handler.getSchema();
        validator = new Validator();
    }

    private List<ValidationError> validate(String query) {
        Document doc = Parser.parse(query);
        return validator.validateDocument(schema, doc, Locale.ROOT);
    }

    // ── Valid queries ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void valid_simpleListQuery() {
        var errors = validate("{ testSchemaCustomer { customer_id first_name } }");
        assertThat(errors).isEmpty();
    }

    @Test
    @Order(2)
    void valid_mutationCreate() {
        var errors = validate("""
                mutation {
                  createTestSchemaCustomer(input: { first_name: "A", last_name: "B" }) {
                    customer_id first_name
                  }
                }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    @Order(3)
    void valid_createOrders_scalarFieldsOnly() {
        var errors = validate("""
                mutation {
                  createTestSchemaOrders(input: { customer_id: 1, total_amount: 99.99 }) {
                    order_id total_amount
                  }
                }
                """);
        assertThat(errors).isEmpty();
    }

    // ── Unknown fields ────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void invalid_unknownFieldInQuery_isRejected() {
        var errors = validate("{ testSchemaCustomer { customer_id nonExistentField } }");
        assertThat(errors).isNotEmpty();
        System.out.println("[10] unknownField error: " + errors.get(0).getMessage());
    }

    @Test
    @Order(11)
    void invalid_unknownInputFieldInMutation_isRejected() {
        var errors = validate("""
                mutation {
                  createTestSchemaCustomer(input: { first_name: "A", unknownColumn: "X" }) {
                    customer_id
                  }
                }
                """);
        assertThat(errors).isNotEmpty();
        System.out.println("[11] unknownColumn error: " + errors.get(0).getMessage());
    }

    // ── Nested insert — the key question ─────────────────────────────────────

    @Test
    @Order(20)
    void nestedInsert_withArrRelInsertInput_isAccepted() {
        // After IntrospectionHandler adds ArrRelInsertInput types, validator MUST accept nested inserts
        var errors = validate("""
                mutation {
                  createTestSchemaOrders(input: {
                    customer_id: 1
                    total_amount: 99.99
                    testSchemaOrderItems: {
                      data: [
                        { product_id: 101, quantity: 2, price: 49.99 }
                      ]
                    }
                  }) { order_id total_amount }
                }
                """);

        System.out.println("[20] Nested insert validation errors: " + errors.stream()
                .map(ValidationError::getMessage).toList());

        assertThat(errors).isEmpty();
    }

    // ── Schema structure ──────────────────────────────────────────────────────

    @Test
    @Order(30)
    void schema_createOrdersInput_currentFields() {
        var type = schema.getTypeMap().get("TestSchemaOrdersCreateInput");
        assertThat(type).isNotNull().isInstanceOf(GraphQLInputObjectType.class);

        var fieldNames = ((GraphQLInputObjectType) type).getFieldDefinitions().stream()
                .map(f -> f.getName())
                .toList();

        System.out.println("[30] CreateTestSchemaOrdersInput fields: " + fieldNames);
        assertThat(fieldNames).contains("customer_id", "total_amount", "testSchemaOrderItems");
    }
}
