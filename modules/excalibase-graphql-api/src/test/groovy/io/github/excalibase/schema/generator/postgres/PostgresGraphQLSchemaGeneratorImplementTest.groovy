package io.github.excalibase.schema.generator.postgres

import graphql.Scalars
import graphql.schema.*
import io.github.excalibase.constant.FieldConstant
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
        connectionField.getArgument(FieldConstant.FIRST) != null
        connectionField.getArgument(FieldConstant.AFTER) != null
        connectionField.getArgument(FieldConstant.LAST) != null
        connectionField.getArgument(FieldConstant.BEFORE) != null

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

    def "should handle enhanced PostgreSQL data types correctly"() {
        given: "a table with enhanced PostgreSQL data types"
        def columns = [
            new ColumnInfo("id", "integer", false, true),
            // JSON types
            new ColumnInfo("json_col", "json", false, true),
            new ColumnInfo("jsonb_col", "jsonb", false, true),
            // Array types
            new ColumnInfo("int_array", "integer[]", false, true),
            new ColumnInfo("text_array", "text[]", false, true),
            // Enhanced date/time types
            new ColumnInfo("timestamptz_col", "timestamptz", false, true),
            new ColumnInfo("timetz_col", "timetz", false, true),
            new ColumnInfo("interval_col", "interval", false, true),
            // Additional numeric types
            new ColumnInfo("bit_col", "bit", false, true),
            new ColumnInfo("varbit_col", "varbit", false, true),
            // Binary and network types
            new ColumnInfo("bytea_col", "bytea", false, true),
            new ColumnInfo("inet_col", "inet", false, true),
            new ColumnInfo("cidr_col", "cidr", false, true),
            new ColumnInfo("macaddr_col", "macaddr", false, true),
            new ColumnInfo("macaddr8_col", "macaddr8", false, true),
            // XML type
            new ColumnInfo("xml_col", "xml", false, true)
        ]
        def tableInfo = new TableInfo("enhanced_types_table", columns, [])
        Map<String, TableInfo> tables = ["enhanced_types_table": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should map enhanced database types to correct GraphQL types"
        GraphQLObjectType enhancedTypesType = schema.getType("enhanced_types_table") as GraphQLObjectType
        enhancedTypesType != null

        // JSON/JSONB types should map to our custom JSON scalar
        enhancedTypesType.getFieldDefinition("json_col").type.name == "JSON"
        enhancedTypesType.getFieldDefinition("jsonb_col").type.name == "JSON"

        // Array types should map to GraphQLList
        enhancedTypesType.getFieldDefinition("int_array").type instanceof GraphQLList
        enhancedTypesType.getFieldDefinition("text_array").type instanceof GraphQLList
        
        // Check that array element types are correct
        GraphQLList intArrayType = enhancedTypesType.getFieldDefinition("int_array").type as GraphQLList
        intArrayType.wrappedType == Scalars.GraphQLInt
        
        GraphQLList textArrayType = enhancedTypesType.getFieldDefinition("text_array").type as GraphQLList
        textArrayType.wrappedType == Scalars.GraphQLString

        // Enhanced date/time types should map to GraphQLString
        enhancedTypesType.getFieldDefinition("timestamptz_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("timetz_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("interval_col").type == Scalars.GraphQLString

        // Bit types should map to GraphQLString
        enhancedTypesType.getFieldDefinition("bit_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("varbit_col").type == Scalars.GraphQLString

        // Binary and network types should map to GraphQLString
        enhancedTypesType.getFieldDefinition("bytea_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("inet_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("cidr_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("macaddr_col").type == Scalars.GraphQLString
        enhancedTypesType.getFieldDefinition("macaddr8_col").type == Scalars.GraphQLString

        // XML type should map to GraphQLString
        enhancedTypesType.getFieldDefinition("xml_col").type == Scalars.GraphQLString
    }

    def "should create appropriate filter types for enhanced PostgreSQL types"() {
        given: "a table with enhanced PostgreSQL data types"
        def columns = [
            new ColumnInfo("json_col", "json", false, true),
            new ColumnInfo("jsonb_col", "jsonb", false, true),
            new ColumnInfo("int_array", "integer[]", false, true),
            new ColumnInfo("timestamptz_col", "timestamptz", false, true),
            new ColumnInfo("inet_col", "inet", false, true)
        ]
        def tableInfo = new TableInfo("filter_test_table", columns, [])
        Map<String, TableInfo> tables = ["filter_test_table": tableInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create appropriate filter input types"
        // Check that JSON filter type exists
        GraphQLInputObjectType jsonFilter = schema.getType("JSONFilter") as GraphQLInputObjectType
        jsonFilter != null
        jsonFilter.getFieldDefinition("eq") != null
        jsonFilter.getFieldDefinition("contains") != null
        jsonFilter.getFieldDefinition("hasKey") != null
        jsonFilter.getFieldDefinition("hasKeys") != null
        jsonFilter.getFieldDefinition("path") != null

        // Check that table filter type includes correct filter assignments
        GraphQLInputObjectType tableFilter = schema.getType("filter_test_tableFilter") as GraphQLInputObjectType
        tableFilter != null
        
        // JSON columns should use JSONFilter
        tableFilter.getFieldDefinition("json_col").type.name == "JSONFilter"
        tableFilter.getFieldDefinition("jsonb_col").type.name == "JSONFilter"
        
        // Array types should use the base type's filter
        tableFilter.getFieldDefinition("int_array").type.name == "IntFilter"
        
        // Enhanced date/time should use DateTimeFilter
        tableFilter.getFieldDefinition("timestamptz_col").type.name == "DateTimeFilter"
        
        // Network types should use StringFilter
        tableFilter.getFieldDefinition("inet_col").type.name == "StringFilter"
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
        GraphQLObjectType pageInfoType = schema.getType(FieldConstant.PAGE_INFO) as GraphQLObjectType
        pageInfoType != null
        pageInfoType.getFieldDefinition(FieldConstant.HAS_NEXT_PAGE) != null
        pageInfoType.getFieldDefinition(FieldConstant.HAS_PREVIOUS_PAGE) != null
        pageInfoType.getFieldDefinition(FieldConstant.START_CURSOR) != null
        pageInfoType.getFieldDefinition(FieldConstant.END_CURSOR) != null
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

    def "should generate schema for views without mutations"() {
        given: "a table and a view"
        def tableColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("email", "character varying(200)", false, false),
            new ColumnInfo("active", "boolean", false, false)
        ]
        def viewColumns = [
            new ColumnInfo("id", "integer", false, false), // Views don't have primary keys
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("email", "character varying(200)", false, false)
        ]

        def tableInfo = new TableInfo("users", tableColumns, [], false) // false = not a view
        def viewInfo = new TableInfo("active_users", viewColumns, [], true) // true = is a view

        Map<String, TableInfo> tables = [
            "users": tableInfo,
            "active_users": viewInfo
        ]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create types for both table and view"
        GraphQLObjectType usersType = schema.getType("users") as GraphQLObjectType
        usersType != null
        usersType.fieldDefinitions.size() == 4

        GraphQLObjectType activeUsersType = schema.getType("active_users") as GraphQLObjectType
        activeUsersType != null
        activeUsersType.fieldDefinitions.size() == 3

        and: "should create query fields for both table and view"
        GraphQLObjectType queryType = schema.getQueryType()
        queryType.getFieldDefinition("users") != null
        queryType.getFieldDefinition("active_users") != null
        queryType.getFieldDefinition("usersConnection") != null
        queryType.getFieldDefinition("active_usersConnection") != null

        and: "should create mutations only for table, not for view"
        GraphQLObjectType mutationType = schema.getMutationType()
        mutationType.getFieldDefinition("createUsers") != null
        mutationType.getFieldDefinition("updateUsers") != null
        mutationType.getFieldDefinition("deleteUsers") != null
        mutationType.getFieldDefinition("createManyUserss") != null
        mutationType.getFieldDefinition("createUsersWithRelations") != null

        // View should not have mutations
        mutationType.getFieldDefinition("createActive_users") == null
        mutationType.getFieldDefinition("updateActive_users") == null
        mutationType.getFieldDefinition("deleteActive_users") == null
        mutationType.getFieldDefinition("createManyActive_userss") == null
        mutationType.getFieldDefinition("createActive_usersWithRelations") == null
    }

    def "should handle schema with only views"() {
        given: "only views in the schema"
        def viewColumns = [
            new ColumnInfo("id", "integer", false, false),
            new ColumnInfo("total_amount", "numeric", false, false),
            new ColumnInfo("month", "timestamp with time zone", false, false)
        ]

        def viewInfo = new TableInfo("monthly_sales", viewColumns, [], true)
        Map<String, TableInfo> tables = ["monthly_sales": viewInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create schema successfully"
        GraphQLObjectType monthlySalesType = schema.getType("monthly_sales") as GraphQLObjectType
        monthlySalesType != null
        monthlySalesType.fieldDefinitions.size() == 3

        and: "should create query fields for the view"
        GraphQLObjectType queryType = schema.getQueryType()
        queryType.getFieldDefinition("monthly_sales") != null
        queryType.getFieldDefinition("monthly_salesConnection") != null

        and: "should not create any mutations"
        GraphQLObjectType mutationType = schema.getMutationType()
        mutationType == null
    }

    def "should handle mixed tables and views with relationships"() {
        given: "tables and views with relationships"
        def userColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("department_id", "integer", false, false)
        ]
        def departmentColumns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying(100)", false, false)
        ]
        def employeeViewColumns = [
            new ColumnInfo("id", "integer", false, false),
            new ColumnInfo("employee_name", "character varying(100)", false, false),
            new ColumnInfo("department_name", "character varying(100)", false, false)
        ]

        def userForeignKeys = [new ForeignKeyInfo("department_id", "departments", "id")]

        def usersTable = new TableInfo("users", userColumns, userForeignKeys, false)
        def departmentsTable = new TableInfo("departments", departmentColumns, [], false)
        def employeeView = new TableInfo("employee_details", employeeViewColumns, [], true)

        Map<String, TableInfo> tables = [
            "users": usersTable,
            "departments": departmentsTable,
            "employee_details": employeeView
        ]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create all types"
        schema.getType("users") != null
        schema.getType("departments") != null
        schema.getType("employee_details") != null

        and: "should create mutations only for tables"
        GraphQLObjectType mutationType = schema.getMutationType()
        mutationType.getFieldDefinition("createUsers") != null
        mutationType.getFieldDefinition("createDepartments") != null
        mutationType.getFieldDefinition("createEmployee_details") == null

        and: "should create relationship fields for tables but not views"
        GraphQLObjectType usersType = schema.getType("users") as GraphQLObjectType
        usersType.getFieldDefinition("departments") != null

        GraphQLObjectType employeeDetailsType = schema.getType("employee_details") as GraphQLObjectType
        // Views don't have foreign key relationships in the traditional sense
        employeeDetailsType.fieldDefinitions.size() == 3 // Only the view columns
    }

    def "should create proper filter types for views"() {
        given: "a view with various column types"
        def viewColumns = [
            new ColumnInfo("id", "integer", false, false),
            new ColumnInfo("name", "character varying(100)", false, false),
            new ColumnInfo("amount", "numeric(10,2)", false, false),
            new ColumnInfo("is_active", "boolean", false, false),
            new ColumnInfo("created_at", "timestamp", false, false)
        ]

        def viewInfo = new TableInfo("summary_view", viewColumns, [], true)
        Map<String, TableInfo> tables = ["summary_view": viewInfo]

        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables)

        then: "should create filter input types for the view"
        GraphQLInputObjectType filterType = schema.getType("summary_viewFilter") as GraphQLInputObjectType
        filterType != null

        and: "filter should have fields for all columns"
        filterType.getFieldDefinition("id") != null
        filterType.getFieldDefinition("name") != null
        filterType.getFieldDefinition("amount") != null
        filterType.getFieldDefinition("is_active") != null
        filterType.getFieldDefinition("created_at") != null

        and: "should support OR operations"
        filterType.getFieldDefinition("or") != null
    }
}
