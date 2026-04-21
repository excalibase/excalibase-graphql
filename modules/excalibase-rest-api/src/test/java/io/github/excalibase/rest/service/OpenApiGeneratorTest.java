package io.github.excalibase.rest.service;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class OpenApiGeneratorTest {

    private SchemaInfo schemaWithCustomersAndOrders() {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema("customers", "public");
        schema.addColumn("customers", "id", "integer");
        schema.addColumn("customers", "email", "text");
        schema.addPrimaryKey("customers", "id");

        schema.setTableSchema("orders", "public");
        schema.addColumn("orders", "id", "bigint");
        schema.addColumn("orders", "customer_id", "integer");
        schema.addColumn("orders", "total", "numeric");
        schema.addColumn("orders", "created_at", "timestamptz");
        schema.addPrimaryKey("orders", "id");
        return schema;
    }

    @Test
    @DisplayName("generate returns OpenAPI 3.0.3 envelope with info and servers")
    void generate_envelope_isOpenApi303() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), "public");

        assertThat(spec).containsEntry("openapi", "3.0.3");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) spec.get("info");
        assertThat(info).containsEntry("title", "Excalibase REST API").containsEntry("version", "1.0.0");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) spec.get("servers");
        assertThat(servers).hasSize(1).first().satisfies(srv -> assertThat(srv).containsEntry("url", "/api/v1"));
    }

    @Test
    @DisplayName("generate emits a path per table with GET/POST/PATCH/DELETE for tables")
    void generate_tablePathsHaveAllCrudOps() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).containsKeys("/customers", "/orders");
        @SuppressWarnings("unchecked")
        Map<String, Object> customersPath = (Map<String, Object>) paths.get("/customers");
        assertThat(customersPath).containsKeys("get", "post", "patch", "delete");
    }

    @Test
    @DisplayName("generate omits write ops for views")
    void generate_viewsOnlyExposeGet() {
        SchemaInfo schema = schemaWithCustomersAndOrders();
        schema.setTableSchema("active_customers", "public");
        schema.addColumn("active_customers", "id", "integer");
        schema.addView("active_customers");

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        @SuppressWarnings("unchecked")
        Map<String, Object> viewPath = (Map<String, Object>) paths.get("/active_customers");

        assertThat(viewPath).containsKey("get").doesNotContainKeys("post", "patch", "delete");
    }

    @Test
    @DisplayName("generate filters out tables in other schemas when defaultSchema is set")
    void generate_skipsTablesInOtherSchemas() {
        SchemaInfo schema = schemaWithCustomersAndOrders();
        schema.setTableSchema("secrets", "private");
        schema.addColumn("secrets", "id", "integer");

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).containsKeys("/customers", "/orders").doesNotContainKey("/secrets");
    }

    @Test
    @DisplayName("generate strips schema prefix from schema-qualified table keys")
    void generate_stripsSchemaPrefixFromPath() {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema("public.items", "public");
        schema.addColumn("public.items", "id", "integer");

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).containsKey("/items");
    }

    @Test
    @DisplayName("generate creates an object schema per table with all columns as properties")
    void generate_schemaHasPropertyPerColumn() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        @SuppressWarnings("unchecked")
        Map<String, Object> ordersSchema = (Map<String, Object>) schemas.get("Orders");
        assertThat(ordersSchema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ordersSchema.get("properties");
        assertThat(props).containsKeys("id", "customer_id", "total", "created_at");
    }

    @Test
    @DisplayName("capitalize expands snake_case to PascalCase in schema component names")
    void generate_snakeCaseTableNameYieldsPascalCaseSchemaName() {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema("order_items", "public");
        schema.addColumn("order_items", "id", "integer");

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertThat(schemas).containsKey("OrderItems").doesNotContainKey("Order_items");
    }

    @Test
    @DisplayName("mapColumnType covers integer, bigint, numeric, real, boolean, jsonb, timestamp, date, uuid, and default")
    void generate_columnTypeMappingCoversAllBranches() {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema("types", "public");
        schema.addColumn("types", "a_int", "integer");
        schema.addColumn("types", "b_int4", "int4");
        schema.addColumn("types", "c_serial", "serial");
        schema.addColumn("types", "d_smallint", "smallint");
        schema.addColumn("types", "e_int2", "int2");
        schema.addColumn("types", "f_bigint", "bigint");
        schema.addColumn("types", "g_int8", "int8");
        schema.addColumn("types", "h_bigserial", "bigserial");
        schema.addColumn("types", "i_numeric", "numeric");
        schema.addColumn("types", "j_decimal", "decimal");
        schema.addColumn("types", "k_float8", "float8");
        schema.addColumn("types", "l_double", "double precision");
        schema.addColumn("types", "m_real", "real");
        schema.addColumn("types", "n_float4", "float4");
        schema.addColumn("types", "o_bool", "boolean");
        schema.addColumn("types", "p_bool2", "bool");
        schema.addColumn("types", "q_jsonb", "jsonb");
        schema.addColumn("types", "r_json", "json");
        schema.addColumn("types", "s_ts", "timestamp");
        schema.addColumn("types", "t_tstz", "timestamptz");
        schema.addColumn("types", "u_date", "date");
        schema.addColumn("types", "v_uuid", "uuid");
        schema.addColumn("types", "w_text", "text");
        schema.addColumn("types", "x_null", null);

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schemas.get("Types")).get("properties");

        assertThat((Map<String, Object>) props.get("a_int")).containsEntry("type", "integer");
        assertThat((Map<String, Object>) props.get("f_bigint")).containsEntry("type", "integer").containsEntry("format", "int64");
        assertThat((Map<String, Object>) props.get("i_numeric")).containsEntry("type", "number");
        assertThat((Map<String, Object>) props.get("m_real")).containsEntry("type", "number").containsEntry("format", "float");
        assertThat((Map<String, Object>) props.get("o_bool")).containsEntry("type", "boolean");
        assertThat((Map<String, Object>) props.get("q_jsonb")).containsEntry("type", "object");
        assertThat((Map<String, Object>) props.get("s_ts")).containsEntry("type", "string").containsEntry("format", "date-time");
        assertThat((Map<String, Object>) props.get("u_date")).containsEntry("type", "string").containsEntry("format", "date");
        assertThat((Map<String, Object>) props.get("v_uuid")).containsEntry("type", "string").containsEntry("format", "uuid");
        assertThat((Map<String, Object>) props.get("w_text")).containsEntry("type", "string");
        assertThat((Map<String, Object>) props.get("x_null")).containsEntry("type", "string");
    }

    @Test
    @DisplayName("GET operation exposes select, order, limit, offset query parameters")
    void generate_getOpExposesListingParams() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> getOp = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("paths")).get("/customers")).get("get");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) getOp.get("parameters");

        assertThat(params).extracting(p -> p.get("name")).containsExactly("select", "order", "limit", "offset");
        assertThat(params).allSatisfy(p -> {
            assertThat(p).containsEntry("in", "query").containsEntry("required", false);
            assertThat((Map<String, Object>) p.get("schema")).containsEntry("type", "string");
        });
    }

    @Test
    @DisplayName("response schemas use $ref to components/schemas/{TypeName}")
    void generate_responsesReferenceComponentSchemas() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), "public");
        @SuppressWarnings("unchecked")
        Map<String, Object> postOp = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("paths")).get("/customers")).get("post");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) postOp.get("requestBody")).get("content"))
                .get("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) schema.get("schema");

        assertThat(ref).containsEntry("$ref", "#/components/schemas/Customers");
    }

    @Test
    @DisplayName("empty schema produces empty paths and schemas but preserves envelope")
    void generate_emptySchemaYieldsEmptyCollections() {
        Map<String, Object> spec = OpenApiGenerator.generate(new SchemaInfo(), "public");

        assertThat((Map<String, Object>) spec.get("paths")).isEmpty();
        assertThat((Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas")).isEmpty();
        assertThat(spec).containsEntry("openapi", "3.0.3");
    }

    @Test
    @DisplayName("generate with null defaultSchema skips every table that has a schema set")
    void generate_nullDefaultSchemaSkipsSchemaQualifiedTables() {
        Map<String, Object> spec = OpenApiGenerator.generate(schemaWithCustomersAndOrders(), null);
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).isEmpty();
    }

    @Test
    @DisplayName("generate includes tables without a registered schema regardless of defaultSchema")
    void generate_tableWithNullSchemaAlwaysIncluded() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("floating", "id", "integer");

        Map<String, Object> spec = OpenApiGenerator.generate(schema, "public");
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).containsKey("/floating");
    }
}
