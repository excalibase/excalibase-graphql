package io.github.excalibase.schema;

import graphql.schema.GraphQLObjectType;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IntrospectionHandlerExposureTest {

    private static final String TABLE = "public.orders";

    private SchemaInfo schemaInfo;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
        schemaInfo.addColumn(TABLE, "id", "integer");
        schemaInfo.addColumn(TABLE, "total", "numeric");
        schemaInfo.setTableSchema(TABLE, "public");
        schemaInfo.addPrimaryKey(TABLE, "id");
    }

    private List<String> mutationFieldNames(TableExposure exposure) {
        GraphQLObjectType mutation = new IntrospectionHandler(schemaInfo, exposure).getSchema().getMutationType();
        return mutation == null ? List.of() : mutation.getFieldDefinitions().stream()
                .map(graphql.schema.GraphQLFieldDefinition::getName).toList();
    }

    @Test
    void buildSchema_whenExposureUnrestricted_emitsEveryMutationField() {
        assertThat(mutationFieldNames(TableExposure.UNRESTRICTED))
                .containsExactlyInAnyOrder("createPublicOrders", "createManyPublicOrders",
                        "updatePublicOrders", "deletePublicOrders");
    }

    @Test
    void buildSchema_whenOnlySelectGranted_emitsNoMutationFieldForThatTable() {
        assertThat(mutationFieldNames(TableExposure.enforcing(Map.of(TABLE, Set.of(RlsOp.SELECT))))).isEmpty();
    }

    @Test
    void buildSchema_whenInsertGranted_emitsOnlyTheCreateFields() {
        assertThat(mutationFieldNames(TableExposure.enforcing(Map.of(TABLE, Set.of(RlsOp.SELECT, RlsOp.INSERT)))))
                .containsExactlyInAnyOrder("createPublicOrders", "createManyPublicOrders");
    }

    @Test
    void buildSchema_whenUpdateGranted_emitsOnlyTheUpdateField() {
        assertThat(mutationFieldNames(TableExposure.enforcing(Map.of(TABLE, Set.of(RlsOp.SELECT, RlsOp.UPDATE)))))
                .containsExactly("updatePublicOrders");
    }

    @Test
    void buildSchema_whenDeleteGranted_emitsOnlyTheDeleteField() {
        assertThat(mutationFieldNames(TableExposure.enforcing(Map.of(TABLE, Set.of(RlsOp.SELECT, RlsOp.DELETE)))))
                .containsExactly("deletePublicOrders");
    }

    @Test
    void buildSchema_whenTableIsReadable_alwaysKeepsItsQueryField() {
        var query = new IntrospectionHandler(schemaInfo, TableExposure.enforcing(Map.of(TABLE, Set.of(RlsOp.SELECT))))
                .getSchema().getQueryType();

        assertThat(query.getFieldDefinitions()).extracting(graphql.schema.GraphQLFieldDefinition::getName)
                .contains("publicOrders");
    }
}
