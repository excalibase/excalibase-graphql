package io.github.excalibase.schema;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Introspection shows the role exactly what it may reach: fields, inputs, aggregates. */
class IntrospectionHandlerAccessTest {

    private static final String TABLE = "public.orders";

    private SchemaInfo schemaInfo;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
        schemaInfo.addColumn(TABLE, "id", "integer");
        schemaInfo.addColumn(TABLE, "total", "numeric");
        schemaInfo.addColumn(TABLE, "status", "text");
        schemaInfo.setTableSchema(TABLE, "public");
        schemaInfo.addPrimaryKey(TABLE, "id");
    }

    private static TableAccess access(Set<RlsOp> operations, boolean aggregations) {
        return TableAccess.enforcing(Map.of(TABLE, new TableAccess.Rights(operations,
                Set.of("total"), Set.of("status"), null, aggregations)));
    }

    private IntrospectionHandler handler(TableAccess access) {
        return new IntrospectionHandler(schemaInfo, access);
    }

    private List<String> mutationFieldNames(TableAccess access) {
        GraphQLObjectType mutation = handler(access).getSchema().getMutationType();
        return mutation == null ? List.of() : names(mutation.getFieldDefinitions());
    }

    private static List<String> names(List<GraphQLFieldDefinition> fields) {
        return fields.stream().map(GraphQLFieldDefinition::getName).toList();
    }

    @Test
    void unrestricted_emitsEveryMutationField() {
        assertThat(mutationFieldNames(TableAccess.UNRESTRICTED))
                .containsExactlyInAnyOrder("createPublicOrders", "createManyPublicOrders",
                        "updatePublicOrders", "deletePublicOrders");
    }

    @Test
    void selectOnly_emitsNoMutationFieldForThatTable() {
        assertThat(mutationFieldNames(access(Set.of(RlsOp.SELECT), false))).isEmpty();
    }

    @Test
    void eachOperation_emitsOnlyItsFields() {
        assertThat(mutationFieldNames(access(Set.of(RlsOp.SELECT, RlsOp.INSERT), false)))
                .containsExactlyInAnyOrder("createPublicOrders", "createManyPublicOrders");
        assertThat(mutationFieldNames(access(Set.of(RlsOp.SELECT, RlsOp.UPDATE), false)))
                .containsExactly("updatePublicOrders");
        assertThat(mutationFieldNames(access(Set.of(RlsOp.SELECT, RlsOp.DELETE), false)))
                .containsExactly("deletePublicOrders");
    }

    @Test
    void inputs_holdOnlyTheColumnsTheRoleMaySet() {
        var schema = handler(access(Set.of(RlsOp.SELECT, RlsOp.INSERT, RlsOp.UPDATE), false)).getSchema();

        GraphQLInputObjectType create = (GraphQLInputObjectType) schema.getType("PublicOrdersCreateInput");
        GraphQLInputObjectType update = (GraphQLInputObjectType) schema.getType("PublicOrdersUpdateInput");
        assertThat(create.getFieldDefinitions()).extracting(GraphQLInputObjectField::getName).containsExactly("total");
        assertThat(update.getFieldDefinitions()).extracting(GraphQLInputObjectField::getName).containsExactly("status");
    }

    @Test
    void aggregatesAndTotalCount_existOnlyWithTheAggregationRight() {
        var without = handler(access(Set.of(RlsOp.SELECT), false)).getSchema();
        var with = handler(access(Set.of(RlsOp.SELECT), true)).getSchema();

        assertThat(names(without.getQueryType().getFieldDefinitions()))
                .contains("publicOrders", "publicOrdersConnection").doesNotContain("publicOrdersAggregate");
        assertThat(((GraphQLObjectType) without.getType("PublicOrdersConnection")).getFieldDefinition("totalCount"))
                .isNull();
        assertThat(names(with.getQueryType().getFieldDefinitions())).contains("publicOrdersAggregate");
        assertThat(((GraphQLObjectType) with.getType("PublicOrdersConnection")).getFieldDefinition("totalCount"))
                .isNotNull();
    }
}
