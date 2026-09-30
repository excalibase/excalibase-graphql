package io.github.excalibase.schema;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
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

    // ---- insert without select ----

    private static final String MESSAGES = "public.messages";

    /** What an insert-only role is served: the table's insert columns typed, nothing listed as readable. */
    private static SchemaInfo insertOnlyView() {
        SchemaInfo view = new SchemaInfo();
        view.setTableSchema(MESSAGES, "public");
        view.addWriteOnlyColumn(MESSAGES, "email", "text");
        view.addWriteOnlyColumn(MESSAGES, "body", "text");
        view.addWriteOnlyColumn(MESSAGES, "source", "text");
        view.addWriteOnlyTable(MESSAGES);
        return view;
    }

    private static TableAccess insertOnly() {
        return TableAccess.enforcing(Map.of(MESSAGES, new TableAccess.Rights(Set.of(RlsOp.INSERT),
                Set.of("email", "body"), Set.of(), null, false)));
    }

    @Test
    void insertOnly_hasNoQueryField_andNoReadType() {
        var schema = new IntrospectionHandler(insertOnlyView(), insertOnly()).getSchema();

        assertThat(names(schema.getQueryType().getFieldDefinitions())).noneMatch(name -> name.contains("Messages"));
        assertThat(schema.getType("PublicMessages")).isNull();
        assertThat(schema.getType("PublicMessagesWhereInput")).isNull();
        assertThat(schema.getType("PublicMessagesConnection")).isNull();
    }

    @Test
    void insertOnly_hasCreateAndCreateMany_returningOnlyTheAffectedRowCount() {
        var schema = new IntrospectionHandler(insertOnlyView(), insertOnly()).getSchema();
        GraphQLObjectType mutation = schema.getMutationType();

        assertThat(names(mutation.getFieldDefinitions()))
                .containsExactlyInAnyOrder("createPublicMessages", "createManyPublicMessages");
        GraphQLObjectType result = (GraphQLObjectType) schema.getType("PublicMessages_InsertResult");
        assertThat(names(result.getFieldDefinitions())).containsExactly("affected_rows");
        assertThat(GraphQLTypeUtil.simplePrint(result.getFieldDefinition("affected_rows").getType())).isEqualTo("Int!");
        assertThat(GraphQLTypeUtil.simplePrint(mutation.getFieldDefinition("createPublicMessages").getType()))
                .isEqualTo("PublicMessages_InsertResult");
        assertThat(GraphQLTypeUtil.simplePrint(mutation.getFieldDefinition("createManyPublicMessages").getType()))
                .isEqualTo("PublicMessages_InsertResult");
    }

    @Test
    void insertOnly_inputHoldsOnlyTheSettableColumns_andNoOnConflict() {
        var schema = new IntrospectionHandler(insertOnlyView(), insertOnly()).getSchema();

        GraphQLInputObjectType create = (GraphQLInputObjectType) schema.getType("PublicMessagesCreateInput");
        assertThat(create.getFieldDefinitions()).extracting(GraphQLInputObjectField::getName)
                .containsExactlyInAnyOrder("email", "body");
        assertThat(schema.getMutationType().getFieldDefinition("createPublicMessages").getArguments())
                .extracting(GraphQLArgument::getName).containsExactly("input");
    }

    @Test
    void selectAndInsert_stillReturnTheRowType() {
        var schema = handler(access(Set.of(RlsOp.SELECT, RlsOp.INSERT), false)).getSchema();

        assertThat(GraphQLTypeUtil.simplePrint(schema.getMutationType().getFieldDefinition("createPublicOrders").getType()))
                .isEqualTo("PublicOrders");
        assertThat(schema.getType("PublicOrders_InsertResult")).isNull();
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
