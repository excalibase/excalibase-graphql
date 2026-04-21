package io.github.excalibase.compiler;

import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.IntValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ProcParam;
import io.github.excalibase.schema.SchemaInfo.ProcedureInfo;
import io.github.excalibase.spi.MutationCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MutationBuilderTest {

    @Mock SqlDialect dialect;
    @Mock FilterBuilder filterBuilder;
    @Mock QueryBuilder queryBuilder;
    @Mock MutationCompiler mutationCompiler;

    private SchemaInfo schemaInfo;
    private MutationBuilder mutationBuilder;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
        lenient().when(dialect.quoteIdentifier(anyString()))
                .thenAnswer(inv -> "\"" + inv.getArgument(0) + "\"");
        lenient().when(dialect.qualifiedTable(anyString(), anyString()))
                .thenAnswer(inv -> "\"" + inv.getArgument(0) + "\".\"" + inv.getArgument(1) + "\"");
        mutationBuilder = new MutationBuilder(schemaInfo, dialect, filterBuilder, "public", queryBuilder, mutationCompiler);
    }

    private Field fieldWithArgs(String name, Argument... args) {
        return Field.newField().name(name).arguments(List.of(args)).build();
    }

    @Nested
    @DisplayName("accessors")
    class Accessors {
        @Test
        @DisplayName("accessor methods return the injected collaborators")
        void accessors_returnInjectedValues() {
            assertThat(mutationBuilder.schemaInfo()).isSameAs(schemaInfo);
            assertThat(mutationBuilder.dialect()).isSameAs(dialect);
            assertThat(mutationBuilder.filterBuilder()).isSameAs(filterBuilder);
            assertThat(mutationBuilder.queryBuilder()).isSameAs(queryBuilder);
            assertThat(mutationBuilder.dbSchema()).isEqualTo("public");
        }
    }

    @Nested
    @DisplayName("qualifiedTable routes through dialect.qualifiedTable")
    class QualifiedTable {
        @Test
        @DisplayName("plain table name uses dbSchema as the schema")
        void plainTableName_usesDbSchema() {
            schemaInfo.addColumn("users", "id", "integer");

            String qt = mutationBuilder.qualifiedTable("users");

            assertThat(qt).isEqualTo("\"public\".\"users\"");
        }

        @Test
        @DisplayName("compound key extracts raw table name")
        void compoundKey_extractsRawTable() {
            schemaInfo.addColumn("tenant.users", "id", "integer");
            schemaInfo.setTableSchema("tenant.users", "tenant");

            String qt = mutationBuilder.qualifiedTable("tenant.users");

            assertThat(qt).isEqualTo("\"tenant\".\"users\"");
        }
    }

    @Nested
    @DisplayName("resolveMutationTable lookup order")
    class ResolveMutationTable {
        @Test
        @DisplayName("PascalCase to snake_case lookup succeeds")
        void pascalCase_resolvesToSnake() {
            schemaInfo.addColumn("user_accounts", "id", "integer");

            assertThat(mutationBuilder.resolveMutationTable("UserAccounts")).isEqualTo("user_accounts");
        }

        @Test
        @DisplayName("falls back to lowercased name when snake_case not found")
        void lowercaseFallback() {
            schemaInfo.addColumn("users", "id", "integer");

            assertThat(mutationBuilder.resolveMutationTable("USERS")).isEqualTo("users");
        }

        @Test
        @DisplayName("compound (schema.table) prefixed names resolve via schemaTypeName")
        void compoundPrefixedName_resolves() {
            schemaInfo.addColumn("tenant.customer", "id", "integer");

            assertThat(mutationBuilder.resolveMutationTable("TenantCustomer")).isEqualTo("tenant.customer");
        }

        @Test
        @DisplayName("unknown type name returns null")
        void unknownName_returnsNull() {
            assertThat(mutationBuilder.resolveMutationTable("Ghost")).isNull();
        }
    }

    @Nested
    @DisplayName("resolveStoredProcedure lookup order")
    class ResolveStoredProcedure {
        @Test
        @DisplayName("snake_case procedure name matches")
        void snakeCaseProc_resolves() {
            schemaInfo.addStoredProcedure("pay_rental", new ProcedureInfo("pay_rental", List.of()));

            assertThat(mutationBuilder.resolveStoredProcedure("PayRental")).isEqualTo("pay_rental");
        }

        @Test
        @DisplayName("all-lowercase proc name matches when snake_case absent")
        void lowercaseFallback() {
            schemaInfo.addStoredProcedure("pingproc", new ProcedureInfo("pingproc", List.of()));

            assertThat(mutationBuilder.resolveStoredProcedure("PingProc")).isEqualTo("pingproc");
        }

        @Test
        @DisplayName("compound key (schema.name) matches via schemaTypeName")
        void compoundKey_resolves() {
            schemaInfo.addStoredProcedure("hana.transfer_funds",
                    new ProcedureInfo("transfer_funds", List.of()));

            assertThat(mutationBuilder.resolveStoredProcedure("HanaTransferFunds")).isEqualTo("hana.transfer_funds");
        }

        @Test
        @DisplayName("unknown procedure returns null")
        void unknownProc_returnsNull() {
            assertThat(mutationBuilder.resolveStoredProcedure("Ghost")).isNull();
        }
    }

    @Nested
    @DisplayName("buildProcedureCallInfo")
    class BuildProcedureCallInfo {
        @Test
        @DisplayName("returns null when the procedure is unknown")
        void unknownProc_returnsNull() {
            Field field = fieldWithArgs("callFoo");
            assertThat(mutationBuilder.buildProcedureCallInfo(field, "foo", Map.of())).isNull();
        }

        @Test
        @DisplayName("IN parameters bind values from matching GraphQL arguments")
        void inParam_boundFromArgument() {
            ProcedureInfo proc = new ProcedureInfo("pay_rental",
                    List.of(new ProcParam("IN", "rental_id", "integer")));
            schemaInfo.addStoredProcedure("pay_rental", proc);

            Argument arg = Argument.newArgument("rental_id", IntValue.of(42)).build();
            Field field = fieldWithArgs("payRental", arg);

            var info = mutationBuilder.buildProcedureCallInfo(field, "pay_rental", Map.of());

            assertThat(info.qualifiedName()).contains("pay_rental");
            assertThat(info.allParams()).hasSize(1);
            assertThat(((Number) info.allParams().get(0).value()).intValue()).isEqualTo(42);
        }

        @Test
        @DisplayName("OUT parameters have null value, still included in the list")
        void outParam_hasNullValue() {
            ProcedureInfo proc = new ProcedureInfo("do_work",
                    List.of(new ProcParam("OUT", "done", "boolean")));
            schemaInfo.addStoredProcedure("do_work", proc);

            var info = mutationBuilder.buildProcedureCallInfo(fieldWithArgs("doWork"), "do_work", Map.of());

            assertThat(info.allParams()).hasSize(1);
            assertThat(info.allParams().get(0).mode()).isEqualTo("OUT");
            assertThat(info.allParams().get(0).value()).isNull();
        }

        @Test
        @DisplayName("INOUT parameters bind value AND declare OUT mode")
        void inoutParam_boundAndRegistered() {
            ProcedureInfo proc = new ProcedureInfo("inout_p",
                    List.of(new ProcParam("INOUT", "counter", "integer")));
            schemaInfo.addStoredProcedure("inout_p", proc);

            Argument arg = Argument.newArgument("counter", IntValue.of(5)).build();
            Field field = fieldWithArgs("inoutP", arg);

            var info = mutationBuilder.buildProcedureCallInfo(field, "inout_p", Map.of());

            assertThat(info.allParams()).hasSize(1);
            assertThat(info.allParams().get(0).mode()).isEqualTo("INOUT");
            assertThat(((Number) info.allParams().get(0).value()).intValue()).isEqualTo(5);
        }

        @Test
        @DisplayName("qualified proc name (schema.name) is preserved in output")
        void qualifiedProcName_preservesSchema() {
            ProcedureInfo proc = new ProcedureInfo("transfer_funds", List.of());
            schemaInfo.addStoredProcedure("hana.transfer_funds", proc);

            var info = mutationBuilder.buildProcedureCallInfo(fieldWithArgs("x"), "hana.transfer_funds", Map.of());

            assertThat(info.qualifiedName()).startsWith("hana.");
        }
    }

    @Nested
    @DisplayName("getEnumCastForMutation")
    class EnumCast {
        @Test
        @DisplayName("emits dialect.enumCast for a declared enum column")
        void enumColumn_usesEnumCast() {
            schemaInfo.addColumn("users", "role", "user_role");
            schemaInfo.addColumnEnumType("users", "role", "user_role");
            schemaInfo.addEnumValue("user_role", "admin");
            schemaInfo.setTableSchema("users", "public");
            when(dialect.enumCast("public", "user_role")).thenReturn("::user_role");

            assertThat(mutationBuilder.getEnumCastForMutation("users", "role")).isEqualTo("::user_role");
        }

        @Test
        @DisplayName("composite type on the column routes through enumCast with the raw type name")
        void compositeTypeColumn_usesEnumCast() {
            schemaInfo.addColumn("users", "addr", "address");
            schemaInfo.addColumnEnumType("users", "addr", "address");
            schemaInfo.addCompositeTypeField("address", "street", "text");
            schemaInfo.setTableSchema("users", "public");
            when(dialect.enumCast("public", "address")).thenReturn("::address");

            assertThat(mutationBuilder.getEnumCastForMutation("users", "addr")).isEqualTo("::address");
        }

        @Test
        @DisplayName("falls back to dialect.paramCast for non-enum, non-composite columns")
        void plainColumn_usesParamCast() {
            schemaInfo.addColumn("users", "id", "uuid");
            when(dialect.paramCast("uuid")).thenReturn("::uuid");

            assertThat(mutationBuilder.getEnumCastForMutation("users", "id")).isEqualTo("::uuid");
        }

        @Test
        @DisplayName("returns empty string when column type has no cast")
        void plainColumn_noCast_returnsEmpty() {
            schemaInfo.addColumn("users", "name", "text");
            when(dialect.paramCast("text")).thenReturn("");

            assertThat(mutationBuilder.getEnumCastForMutation("users", "name")).isEmpty();
        }

        @Test
        @DisplayName("returns empty string for unknown column")
        void unknownColumn_returnsEmpty() {
            assertThat(mutationBuilder.getEnumCastForMutation("ghost", "x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("convertCompositeValue")
    class ConvertComposite {
        @Test
        @DisplayName("null value returns null")
        void nullValue_returnsNull() {
            assertThat(mutationBuilder.convertCompositeValue("users", "addr", null)).isNull();
        }

        @Test
        @DisplayName("non-composite column returns the value untouched")
        void nonCompositeColumn_returnsValue() {
            schemaInfo.addColumn("users", "id", "integer");

            assertThat(mutationBuilder.convertCompositeValue("users", "id", 42)).isEqualTo(42);
        }

        @Test
        @DisplayName("composite column with a Map value builds tuple string in field declaration order")
        void mapValue_buildsTupleString() {
            schemaInfo.addColumn("users", "addr", "address");
            schemaInfo.addColumnEnumType("users", "addr", "address");
            schemaInfo.addCompositeTypeField("address", "street", "text");
            schemaInfo.addCompositeTypeField("address", "zip", "text");

            Object result = mutationBuilder.convertCompositeValue("users", "addr",
                    Map.of("street", "Main", "zip", "11111"));

            assertThat(result).isEqualTo("(Main,11111)");
        }

        @Test
        @DisplayName("composite column with JSON object string parses and builds tuple string")
        void jsonStringValue_parsesAndBuilds() {
            schemaInfo.addColumn("users", "addr", "address");
            schemaInfo.addColumnEnumType("users", "addr", "address");
            schemaInfo.addCompositeTypeField("address", "street", "text");
            schemaInfo.addCompositeTypeField("address", "zip", "text");

            Object result = mutationBuilder.convertCompositeValue("users", "addr",
                    "{\"street\":\"Main\",\"zip\":\"11111\"}");

            assertThat(result).isEqualTo("(Main,11111)");
        }

        @Test
        @DisplayName("composite column with invalid JSON string returns original value")
        void invalidJsonString_returnsOriginal() {
            schemaInfo.addColumn("users", "addr", "address");
            schemaInfo.addColumnEnumType("users", "addr", "address");
            schemaInfo.addCompositeTypeField("address", "street", "text");

            Object result = mutationBuilder.convertCompositeValue("users", "addr", "{not-valid-json");

            assertThat(result).isEqualTo("{not-valid-json");
        }
    }

    @Nested
    @DisplayName("findArg + extractObjectFields")
    class ArgExtraction {
        @Test
        @DisplayName("findArg returns null when the argument is absent")
        void findArg_missingReturnsNull() {
            assertThat(mutationBuilder.findArg(fieldWithArgs("f"), "missing")).isNull();
        }

        @Test
        @DisplayName("findArg returns the matching argument")
        void findArg_presentReturnsArg() {
            Argument arg = Argument.newArgument("x", IntValue.of(1)).build();

            assertThat(mutationBuilder.findArg(fieldWithArgs("f", arg), "x")).isSameAs(arg);
        }

        @Test
        @DisplayName("extractObjectFields returns empty map for non-object, non-variable values")
        void extractObjectFields_nonObject_returnsEmpty() {
            Value<?> value = StringValue.newStringValue("hi").build();

            assertThat(mutationBuilder.extractObjectFields(value, Map.of())).isEmpty();
        }

        @Test
        @DisplayName("extractObjectFields expands ObjectValue into a flat map")
        void extractObjectFields_objectValue_flattened() {
            ObjectValue ov = ObjectValue.newObjectValue()
                    .objectField(ObjectField.newObjectField().name("id").value(IntValue.of(1)).build())
                    .objectField(ObjectField.newObjectField().name("name").value(StringValue.newStringValue("A").build()).build())
                    .build();

            Map<String, Object> result = mutationBuilder.extractObjectFields(ov, Map.of());

            assertThat(result).containsKey("id").containsEntry("name", "A");
            assertThat(((Number) result.get("id")).intValue()).isEqualTo(1);
        }

        @Test
        @DisplayName("extractObjectFields resolves a VariableReference to a Map value")
        void extractObjectFields_variableReference_resolves() {
            VariableReference vr = VariableReference.newVariableReference().name("v").build();

            Map<String, Object> result = mutationBuilder.extractObjectFields(vr, Map.of("v", Map.of("k", "v")));

            assertThat(result).containsEntry("k", "v");
        }
    }

    @Nested
    @DisplayName("compileMutation delegates to MutationCompiler")
    class CompileDelegation {
        @Test
        @DisplayName("compileMutation delegates with all arguments")
        void compileMutation_delegates() {
            Field field = fieldWithArgs("createUser");
            Map<String, Object> params = Map.of();
            Map<String, Object> vars = Map.of();

            mutationBuilder.compileMutation(field, "createUser", params, vars);

            org.mockito.Mockito.verify(mutationCompiler)
                    .compileMutation(field, "createUser", params, vars, mutationBuilder);
        }

        @Test
        @DisplayName("compileMutationFragment delegates with all arguments")
        void compileMutationFragment_delegates() {
            Field field = fieldWithArgs("createUser");

            mutationBuilder.compileMutationFragment(field, "createUser", Map.of(), Map.of());

            org.mockito.Mockito.verify(mutationCompiler)
                    .compileMutationFragment(eq(field), eq("createUser"), any(), any(), eq(mutationBuilder));
        }
    }
}
