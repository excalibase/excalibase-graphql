package io.github.excalibase.access;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import io.github.excalibase.schema.IntrospectionHandler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import static io.github.excalibase.access.AccessFixture.CUSTOMERS;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.selectAll;
import static io.github.excalibase.access.AccessFixture.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A nested insert goes through a relationship when the role may insert into both tables, as in Hasura:
 * selecting either table is not needed, and a relationship it cannot insert through is not offered.
 */
class NestedInsertAccessTest {

    private static final SchemaInfo REFLECTED = AccessFixture.schema();
    private static final String INSERT_ALL = "\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{}}";
    private static final String ORDERS_OF_CUSTOMER = CUSTOMERS + ".publicOrders";
    private static final String CUSTOMER_OF_ORDER = ORDERS + ".publicCustomerId";

    private static AccessPlan plan(String... entries) {
        return AccessPlan.forRole(REFLECTED, user(entries));
    }

    private static GraphQLSchema schemaOf(AccessPlan plan) {
        return new IntrospectionHandler(plan.view(), plan.access(), plan.functions()).getSchema();
    }

    private static GraphQLInputObjectType input(GraphQLSchema schema, String name) {
        return (GraphQLInputObjectType) schema.getType(name);
    }

    @Test
    void insertOnBothTables_isEnough_withoutSelectingEither() {
        AccessPlan plan = plan(entry(CUSTOMERS, INSERT_ALL), entry(ORDERS, INSERT_ALL));

        assertThat(plan.view().getArrayInsertRelationships()).containsOnlyKeys(ORDERS_OF_CUSTOMER);
        assertThat(plan.view().getArrayInsertRelationship(CUSTOMERS, "publicOrders").childTable()).isEqualTo(ORDERS);
        assertThat(plan.view().getObjectInsertRelationships()).containsOnlyKeys(CUSTOMER_OF_ORDER);
        assertThat(plan.view().getObjectInsertRelationship(ORDERS, "publicCustomerId").refTable()).isEqualTo(CUSTOMERS);
        assertThat(plan.view().getAllForwardFks()).isEmpty();
        assertThat(plan.view().getAllReverseFks()).isEmpty();
    }

    @Test
    void withoutInsertOnTheOtherTable_thereIsNoNestedInsert() {
        AccessPlan customersOnly = plan(entry(CUSTOMERS, INSERT_ALL), entry(ORDERS, selectAll()));
        AccessPlan ordersOnly = plan(entry(CUSTOMERS, selectAll()), entry(ORDERS, INSERT_ALL));

        assertThat(customersOnly.view().getArrayInsertRelationships()).isEmpty();
        assertThat(customersOnly.view().getObjectInsertRelationships()).isEmpty();
        assertThat(ordersOnly.view().getArrayInsertRelationships()).isEmpty();
        assertThat(ordersOnly.view().getObjectInsertRelationships()).isEmpty();
    }

    @Test
    void selectAndInsert_nestsThroughTheSameRelationships() {
        String both = selectAll() + "," + INSERT_ALL;
        AccessPlan plan = plan(entry(CUSTOMERS, both), entry(ORDERS, both));

        assertThat(plan.view().getArrayInsertRelationships()).containsOnlyKeys(ORDERS_OF_CUSTOMER);
        assertThat(plan.view().getObjectInsertRelationships()).containsOnlyKeys(CUSTOMER_OF_ORDER);
    }

    @Test
    void allAccess_nestsThroughEveryForeignKey() {
        AccessPlan plan = AccessPlan.allAccess(REFLECTED);

        assertThat(plan.view().getArrayInsertRelationships()).containsOnlyKeys(ORDERS_OF_CUSTOMER);
        assertThat(plan.view().getObjectInsertRelationships()).containsOnlyKeys(CUSTOMER_OF_ORDER);
    }

    @Test
    void theInsertInputs_offerBothDirections_typedByTheOtherTablesInsertInput() {
        GraphQLSchema schema = schemaOf(plan(entry(CUSTOMERS, INSERT_ALL), entry(ORDERS, INSERT_ALL)));

        GraphQLInputType arrayRelation = input(schema, "PublicCustomersCreateInput").getField("publicOrders").getType();
        assertThat(GraphQLTypeUtil.simplePrint(arrayRelation)).isEqualTo("PublicOrdersArrRelInsertInput");
        GraphQLType rows = input(schema, "PublicOrdersArrRelInsertInput").getField("data").getType();
        assertThat(GraphQLTypeUtil.simplePrint(rows)).isEqualTo("[PublicOrdersCreateInput!]!");

        GraphQLInputType objectRelation = input(schema, "PublicOrdersCreateInput").getField("publicCustomerId").getType();
        assertThat(GraphQLTypeUtil.simplePrint(objectRelation)).isEqualTo("PublicCustomersObjRelInsertInput");
        GraphQLType row = input(schema, "PublicCustomersObjRelInsertInput").getField("data").getType();
        assertThat(row).isInstanceOf(GraphQLNonNull.class);
        assertThat(GraphQLTypeUtil.simplePrint(row)).isEqualTo("PublicCustomersCreateInput!");
        assertThat(GraphQLTypeUtil.unwrapNonNull(rows)).isInstanceOf(GraphQLList.class);
    }

    @Test
    void theInsertInputs_offerNoRelationshipTheRoleCannotInsertThrough() {
        GraphQLSchema schema = schemaOf(plan(entry(CUSTOMERS, selectAll() + "," + INSERT_ALL),
                entry(ORDERS, selectAll())));

        assertThat(input(schema, "PublicCustomersCreateInput").getField("publicOrders")).isNull();
        assertThat(schema.getType("PublicOrdersArrRelInsertInput")).isNull();
        assertThat(schema.getType("PublicCustomersObjRelInsertInput")).isNull();
    }

    @Test
    void aStoreWithoutNestedInserts_offersNone() {
        AccessPlan plan = AccessPlan.allAccess(REFLECTED);
        GraphQLSchema schema = new IntrospectionHandler(plan.view(), plan.access(), plan.functions(), false)
                .getSchema();

        assertThat(input(schema, "PublicCustomersCreateInput").getField("publicOrders")).isNull();
        assertThat(input(schema, "PublicOrdersCreateInput").getField("publicCustomerId")).isNull();
        assertThat(schema.getType("PublicOrdersArrRelInsertInput")).isNull();
    }
}
