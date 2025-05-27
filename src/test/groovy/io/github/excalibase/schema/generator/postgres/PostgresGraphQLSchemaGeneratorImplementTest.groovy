package io.github.excalibase.schema.generator.postgres

import graphql.Scalars
import graphql.schema.*
import io.github.excalibase.exception.EmptySchemaException
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import spock.lang.Specification

class PostgresGraphQLSchemaGeneratorImplementTest extends Specification {

    PostgresGraphQLSchemaGeneratorImplement generator

    def setup() {
        generator = new PostgresGraphQLSchemaGeneratorImplement()
    }

    def "should throw exception for empty table map"() {
        given: "an empty table map"
        Map<String, TableInfo> tables = [:]

        when: "generating schema"
        generator.generateSchema(tables)

        then: "should throw EmptySchemaException"
        def error = thrown(EmptySchemaException)
        error.message == "Cannot generate schema with empty postgres schema"
    }

    def "should throw exception for null table map"() {
        given: "an empty table map"
        Map<String, TableInfo> tables = null

        when: "generating schema"
        generator.generateSchema(tables)

        then: "should throw EmptySchemaException"
        def error = thrown(EmptySchemaException)
        error.message == "Cannot generate schema with empty postgres schema"
    }


    def "should generate schema for simple table with basic columns"() {
        given: "a simple table with various column types"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("description", "text", false, true),
            new ColumnInfo("is_active", "boolean", false, true),
            new ColumnInfo("price", "decimal(10,2)", false, true)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create proper GraphQL types"
        schema != null
        schema.queryType.name == "Query"
        schema.mutationType.name == "Mutation"

        and: "should contain the products type"
        GraphQLObjectType productsType = schema.getType("products") as GraphQLObjectType
        productsType != null
        productsType.name == "products"
        productsType.fieldDefinitions.size() == 5

        and: "should have correct field types"
        GraphQLFieldDefinition idField = productsType.getFieldDefinition("id")
        idField.type instanceof GraphQLNonNull
        ((GraphQLNonNull) idField.type).wrappedType == Scalars.GraphQLInt

        GraphQLFieldDefinition nameField = productsType.getFieldDefinition("name")
        nameField.type instanceof GraphQLNonNull
        ((GraphQLNonNull) nameField.type).wrappedType == Scalars.GraphQLString

        GraphQLFieldDefinition descriptionField = productsType.getFieldDefinition("description")
        descriptionField.type == Scalars.GraphQLString

        GraphQLFieldDefinition isActiveField = productsType.getFieldDefinition("is_active")
        isActiveField.type == Scalars.GraphQLBoolean

        GraphQLFieldDefinition priceField = productsType.getFieldDefinition("price")
        priceField.type == Scalars.GraphQLFloat
    }

    def "should generate schema with foreign key relationships"() {
        given: "tables with foreign key relationships"
        def customerColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("email", "character varying(255)", false, true)
        ]
        def customerTable = new TableInfo("customers", customerColumns, [])

        def orderColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("customer_id", "integer", false, false),
            new ColumnInfo("order_date", "timestamp", false, true),
            new ColumnInfo("total_amount", "decimal(10,2)", false, true)
        ]
        def orderForeignKeys = [
            new ForeignKeyInfo("customer_id", "customers", "id")
        ]
        def orderTable = new TableInfo("orders", orderColumns, orderForeignKeys)

        Map<String, TableInfo> tables = [
            "customers": customerTable,
            "orders": orderTable
        ]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create relationship fields"
        GraphQLObjectType ordersType = schema.getType("orders") as GraphQLObjectType
        ordersType != null
        
        // Should have regular columns plus relationship field
        ordersType.fieldDefinitions.size() == 5 // 4 columns + 1 relationship

        GraphQLFieldDefinition customerRelation = ordersType.getFieldDefinition("customers")
        customerRelation != null
        customerRelation.getName() == "customers"
        customerRelation.getType() instanceof  GraphQLObjectType

    }

    def "should generate proper query fields with filtering arguments"() {
        given: "a table with different column types"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("price", "decimal(10,2)", false, true),
            new ColumnInfo("created_at", "timestamp", false, true)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create query field with proper arguments"
        GraphQLFieldDefinition queryField = schema.queryType.getFieldDefinition("products")
        queryField != null
        queryField.type instanceof GraphQLList

        and: "should have filtering arguments for each column"
        // Basic filtering
        queryField.getArgument("id") != null
        queryField.getArgument("name") != null
        queryField.getArgument("price") != null
        queryField.getArgument("created_at") != null

        and: "should have string-specific filtering for name"
        queryField.getArgument("name_contains") != null
        queryField.getArgument("name_startsWith") != null
        queryField.getArgument("name_endsWith") != null

        and: "should have numeric filtering for price"
        queryField.getArgument("price_gt") != null
        queryField.getArgument("price_gte") != null
        queryField.getArgument("price_lt") != null
        queryField.getArgument("price_lte") != null

        and: "should have pagination arguments"
        queryField.getArgument("limit") != null
        queryField.getArgument("offset") != null

        and: "should have orderBy argument"
        queryField.getArgument("orderBy") != null
    }

    def "should generate connection fields for cursor-based pagination"() {
        given: "a simple table"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create connection field"
        GraphQLFieldDefinition connectionField = schema.queryType.getFieldDefinition("productsConnection")
        connectionField != null
        connectionField.getName() == "productsConnection"

        and: "should have cursor pagination arguments"
        connectionField.getArgument("first") != null
        connectionField.getArgument("after") != null
        connectionField.getArgument("last") != null
        connectionField.getArgument("before") != null

        and: "should create Edge and Connection types"
        GraphQLObjectType edgeType = schema.getType("productsEdge") as GraphQLObjectType
        edgeType != null
        edgeType.getFieldDefinition("node") != null
        edgeType.getFieldDefinition("cursor") != null

        GraphQLObjectType connectionType = schema.getType("productsConnection") as GraphQLObjectType
        connectionType != null
        connectionType.getFieldDefinition("edges") != null
        connectionType.getFieldDefinition("pageInfo") != null
        connectionType.getFieldDefinition("totalCount") != null
    }

    def "should generate proper mutation fields"() {
        given: "a table with columns"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("description", "text", false, true)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create CRUD mutation fields"
        GraphQLFieldDefinition createField = schema.mutationType.getFieldDefinition("createProducts")
        createField != null
        createField.type instanceof GraphQLObjectType

        GraphQLFieldDefinition updateField = schema.mutationType.getFieldDefinition("updateProducts")
        updateField != null
        updateField.type instanceof GraphQLObjectType

        GraphQLFieldDefinition deleteField = schema.mutationType.getFieldDefinition("deleteProducts")
        deleteField != null
        deleteField.type == Scalars.GraphQLBoolean

        GraphQLFieldDefinition bulkCreateField = schema.mutationType.getFieldDefinition("createManyProductss")
        bulkCreateField != null
        bulkCreateField.type instanceof GraphQLList

        GraphQLFieldDefinition createWithRelationsField = schema.mutationType.getFieldDefinition("createProductsWithRelations")
        createWithRelationsField != null
        createWithRelationsField.type instanceof GraphQLObjectType
    }

    def "should create proper input types for mutations"() {
        given: "a table with columns including primary key"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("description", "text", false, true)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create CREATE input type with all optional fields"
        GraphQLInputObjectType createInputType = schema.getType("productsCreateInput") as GraphQLInputObjectType
        createInputType != null
        createInputType.fieldDefinitions.size() == 3

        // All fields should be optional in create input
        createInputType.fieldDefinitions.every { field ->
            !(field.type instanceof GraphQLNonNull)
        }

        and: "should create UPDATE input type with required primary key"
        GraphQLInputObjectType updateInputType = schema.getType("productsUpdateInput") as GraphQLInputObjectType
        updateInputType != null
        updateInputType.fieldDefinitions.size() == 3

        GraphQLInputObjectField idField = updateInputType.getFieldDefinition("id")
        idField.type instanceof GraphQLNonNull // Primary key should be required in update

        GraphQLInputObjectField nameField = updateInputType.getFieldDefinition("name")
        !(nameField.type instanceof GraphQLNonNull) // Non-primary fields should be optional

        GraphQLInputObjectField descriptionField = updateInputType.getFieldDefinition("description")
        !(descriptionField.type instanceof GraphQLNonNull) // Non-primary fields should be optional
    }

    def "should create relationship input types for complex mutations"() {
        given: "tables with relationships"
        def customerColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false)
        ]
        def customerTable = new TableInfo("customers", customerColumns, [])

        def orderColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("customer_id", "integer", false, false),
            new ColumnInfo("total", "decimal(10,2)", false, true)
        ]
        def orderForeignKeys = [
            new ForeignKeyInfo("customer_id", "customers", "id")
        ]
        def orderTable = new TableInfo("orders", orderColumns, orderForeignKeys)

        Map<String, TableInfo> tables = [
            "customers": customerTable,
            "orders": orderTable
        ]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create relationship input type"
        GraphQLInputObjectType orderRelationshipInput = schema.getType("ordersRelationshipInput") as GraphQLInputObjectType
        orderRelationshipInput != null

        // Should have regular fields
        orderRelationshipInput.getFieldDefinition("id") != null
        orderRelationshipInput.getFieldDefinition("customer_id") != null
        orderRelationshipInput.getFieldDefinition("total") != null

        // Should have relationship fields
        orderRelationshipInput.getFieldDefinition("customers_connect") != null
        orderRelationshipInput.getFieldDefinition("customers_create") != null

        and: "should create connect input type"
        GraphQLInputObjectType connectInputType = schema.getType("ordersCustomersConnectInput") as GraphQLInputObjectType
        connectInputType != null
        connectInputType.getFieldDefinition("id") != null
        connectInputType.getFieldDefinition("id").type instanceof GraphQLNonNull
    }

    def "should handle various PostgreSQL data types correctly"() {
        given: "a table with various PostgreSQL data types"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("big_id", "bigint", false, true),
            new ColumnInfo("small_id", "smallint", false, true),
            new ColumnInfo("text_col", "text", false, true),
            new ColumnInfo("varchar_col", "character varying(50)", false, true),
            new ColumnInfo("decimal_col", "decimal(10,2)", false, true),
            new ColumnInfo("numeric_col", "numeric(15,5)", false, true),
            new ColumnInfo("real_col", "real", false, true),
            new ColumnInfo("double_col", "double precision", false, true),
            new ColumnInfo("boolean_col", "boolean", false, true),
            new ColumnInfo("uuid_col", "uuid", false, true),
            new ColumnInfo("timestamp_col", "timestamp", false, true),
            new ColumnInfo("date_col", "date", false, true),
            new ColumnInfo("time_col", "time", false, true)
        ]
        def tableInfo = new TableInfo("data_types_table", columns, [])
        Map<String, TableInfo> tables = ["data_types_table": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should map database types to correct GraphQL types"
        GraphQLObjectType dataTypesType = schema.getType("data_types_table") as GraphQLObjectType
        dataTypesType != null

        // Integer types should map to GraphQLInt
        ((GraphQLNonNull) dataTypesType.getFieldDefinition("id").type).wrappedType == Scalars.GraphQLInt
        dataTypesType.getFieldDefinition("big_id").type == Scalars.GraphQLInt
        dataTypesType.getFieldDefinition("small_id").type == Scalars.GraphQLInt

        // Text types should map to GraphQLString
        dataTypesType.getFieldDefinition("text_col").type == Scalars.GraphQLString
        dataTypesType.getFieldDefinition("varchar_col").type == Scalars.GraphQLString

        // Numeric types should map to GraphQLFloat
        dataTypesType.getFieldDefinition("decimal_col").type == Scalars.GraphQLFloat
        dataTypesType.getFieldDefinition("numeric_col").type == Scalars.GraphQLFloat
        dataTypesType.getFieldDefinition("real_col").type == Scalars.GraphQLFloat
        dataTypesType.getFieldDefinition("double_col").type == Scalars.GraphQLFloat

        // Boolean should map to GraphQLBoolean
        dataTypesType.getFieldDefinition("boolean_col").type == Scalars.GraphQLBoolean

        // UUID should map to GraphQLID
        dataTypesType.getFieldDefinition("uuid_col").type == Scalars.GraphQLID

        // Date/time types should map to GraphQLString
        dataTypesType.getFieldDefinition("timestamp_col").type == Scalars.GraphQLString
        dataTypesType.getFieldDefinition("date_col").type == Scalars.GraphQLString
        dataTypesType.getFieldDefinition("time_col").type == Scalars.GraphQLString
    }

    def "should create OrderDirection enum and PageInfo type"() {
        given: "any table"
        def columns = [new ColumnInfo("id", "integer", true, false)]
        def tableInfo = new TableInfo("test_table", columns, [])
        Map<String, TableInfo> tables = ["test_table": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create OrderDirection enum"
        GraphQLEnumType orderDirectionEnum = schema.getType("OrderDirection") as GraphQLEnumType
        orderDirectionEnum != null
        orderDirectionEnum.values.size() == 2
        orderDirectionEnum.getValue("ASC") != null
        orderDirectionEnum.getValue("DESC") != null

        and: "should create PageInfo type"
        GraphQLObjectType pageInfoType = schema.getType("PageInfo") as GraphQLObjectType
        pageInfoType != null
        pageInfoType.getFieldDefinition("hasNextPage") != null
        pageInfoType.getFieldDefinition("hasPreviousPage") != null
        pageInfoType.getFieldDefinition("startCursor") != null
        pageInfoType.getFieldDefinition("endCursor") != null
    }

    def "should create OrderByInput type for each table"() {
        given: "a table with multiple columns"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("created_at", "timestamp", false, true)
        ]
        def tableInfo = new TableInfo("products", columns, [])
        Map<String, TableInfo> tables = ["products": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create OrderByInput type"
        GraphQLInputObjectType orderByInputType = schema.getType("productsOrderByInput") as GraphQLInputObjectType
        orderByInputType != null
        orderByInputType.fieldDefinitions.size() == 3

        orderByInputType.getFieldDefinition("id") != null
        orderByInputType.getFieldDefinition("name") != null
        orderByInputType.getFieldDefinition("created_at") != null

        // All order fields should reference OrderDirection
        orderByInputType.fieldDefinitions.every { field ->
            field.getDescription() == "Order by " + field.getName()
            field.getType()["name"] == "OrderDirection"
        }
    }

    def "should handle table with composite primary key"() {
        given: "a table with composite primary key"
        def columns = [
            new ColumnInfo("customer_id", "integer", true, false),
            new ColumnInfo("product_id", "integer", true, false),
            new ColumnInfo("quantity", "integer", false, false)
        ]
        def tableInfo = new TableInfo("order_items", columns, [])
        Map<String, TableInfo> tables = ["order_items": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create proper types with both primary keys as non-null"
        GraphQLObjectType orderItemsType = schema.getType("order_items") as GraphQLObjectType
        orderItemsType != null

        GraphQLFieldDefinition customerIdField = orderItemsType.getFieldDefinition("customer_id")
        customerIdField.type instanceof GraphQLNonNull

        GraphQLFieldDefinition productIdField = orderItemsType.getFieldDefinition("product_id")
        productIdField.type instanceof GraphQLNonNull

        GraphQLFieldDefinition quantityField = orderItemsType.getFieldDefinition("quantity")
        quantityField.type instanceof GraphQLNonNull

        and: "should require both primary keys in update input"
        GraphQLInputObjectType updateInputType = schema.getType("order_itemsUpdateInput") as GraphQLInputObjectType
        updateInputType != null

        updateInputType.getFieldDefinition("customer_id").type instanceof GraphQLNonNull
        updateInputType.getFieldDefinition("product_id").type instanceof GraphQLNonNull
        !(updateInputType.getFieldDefinition("quantity").type instanceof GraphQLNonNull)
    }

    def "should handle reverse relationships in relationship input"() {
        given: "tables with reverse relationships"
        def customerColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false)
        ]
        def customerTable = new TableInfo("customers", customerColumns, [])

        def orderColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("customer_id", "integer", false, false)
        ]
        def orderForeignKeys = [
            new ForeignKeyInfo("customer_id", "customers", "id")
        ]
        def orderTable = new TableInfo("orders", orderColumns, orderForeignKeys)

        Map<String, TableInfo> tables = [
            "customers": customerTable,
            "orders": orderTable
        ]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "customer relationship input should include reverse relationship to orders"
        GraphQLInputObjectType customerRelationshipInput = schema.getType("customersRelationshipInput") as GraphQLInputObjectType
        customerRelationshipInput != null

        // Should have field to create many orders when creating customer
        customerRelationshipInput.getFieldDefinition("orders_createMany") != null
        GraphQLInputObjectField ordersCreateManyField = customerRelationshipInput.getFieldDefinition("orders_createMany")
        ordersCreateManyField.type instanceof GraphQLList
    }

    def "should handle table with no foreign keys"() {
        given: "a table with no foreign keys"
        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false)
        ]
        def tableInfo = new TableInfo("standalone_table", columns, [])
        Map<String, TableInfo> tables = ["standalone_table": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create proper schema without relationship fields"
        GraphQLObjectType standaloneType = schema.getType("standalone_table") as GraphQLObjectType
        standaloneType != null
        standaloneType.fieldDefinitions.size() == 2 // Only the actual columns, no relationships

        and: "relationship input should only have basic fields"
        GraphQLInputObjectType relationshipInput = schema.getType("standalone_tableRelationshipInput") as GraphQLInputObjectType
        relationshipInput != null
        relationshipInput.fieldDefinitions.size() == 2 // Only the actual columns
    }
}
