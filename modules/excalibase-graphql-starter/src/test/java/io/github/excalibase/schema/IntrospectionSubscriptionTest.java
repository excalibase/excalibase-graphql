package io.github.excalibase.schema;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EXC-559: introspection describes subscriptions and the aggregate's arguments the engine serves. */
class IntrospectionSubscriptionTest {

    private SchemaInfo info;

    @BeforeEach
    void setUp() {
        info = new SchemaInfo();
        info.addColumn("public.orders", "id", "integer");
        info.addColumn("public.orders", "total", "numeric");
        info.addColumn("public.orders", "note", "text");
        info.addPrimaryKey("public.orders", "id");
        info.setTableSchema("public.orders", "public");
    }

    private GraphQLSchema schema() {
        return new IntrospectionHandler(info).getSchema();
    }

    @Test
    void everyTable_hasAChangesSubscription() {
        GraphQLObjectType subscription = schema().getSubscriptionType();

        assertThat(subscription).isNotNull();
        GraphQLFieldDefinition changes = subscription.getFieldDefinition("publicOrdersChanges");
        assertThat(changes).isNotNull();
        GraphQLObjectType event = (GraphQLObjectType) changes.getType();
        assertThat(event.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .contains("operation", "table", "data", "timestamp");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSubscriptionType_isReportedByIntrospection() {
        Map<String, Object> result = new IntrospectionHandler(info)
                .execute("{ __schema { subscriptionType { name fields { name } } } }", Map.of());

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> subscriptionType = (Map<String, Object>) ((Map<String, Object>) data.get("__schema"))
                .get("subscriptionType");
        assertThat(subscriptionType).containsEntry("name", "Subscription");
        assertThat(subscriptionType.get("fields").toString()).contains("publicOrdersChanges");
    }

    @Test
    void aViewHasNoChangesSubscription() {
        info.addColumn("public.big_orders", "id", "integer");
        info.setTableSchema("public.big_orders", "public");
        info.addView("public.big_orders");

        assertThat(schema().getSubscriptionType().getFieldDefinition("publicBigOrdersChanges")).isNull();
    }

    @Test
    void withoutTables_thereIsNoSubscriptionType() {
        assertThat(new IntrospectionHandler(new SchemaInfo()).getSchema().getSubscriptionType()).isNull();
    }

    @Test
    void theAggregate_takesWhere_andOffersColumnAggregates() {
        GraphQLFieldDefinition aggregate = schema().getQueryType().getFieldDefinition("publicOrdersAggregate");

        assertThat(aggregate.getArgument("where")).isNotNull();
        GraphQLObjectType type = (GraphQLObjectType) aggregate.getType();
        assertThat(type.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .contains("count", "sum", "avg", "min", "max");
        GraphQLObjectType sum = (GraphQLObjectType) type.getFieldDefinition("sum").getType();
        assertThat(sum.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .containsExactlyInAnyOrder("id", "total");
    }
}
