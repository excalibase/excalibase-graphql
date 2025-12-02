package io.github.excalibase.postgres.generator

import graphql.schema.*
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.CompositeTypeAttribute
import io.github.excalibase.model.CustomCompositeTypeInfo
import io.github.excalibase.model.CustomEnumInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector
import spock.lang.Specification
import spock.lang.Subject

/**
 * Test suite for PostgreSQL subscription schema generation
 *
 * Tests that subscription types are properly generated with table columns
 */
class PostgresSubscriptionSchemaTest extends Specification {

    @Subject
    PostgresGraphQLSchemaGeneratorImplement generator
    IDatabaseSchemaReflector mockSchemaReflector

    def setup() {
        def mockServiceLookup = Mock(io.github.excalibase.service.ServiceLookup)
        def mockAppConfig = Mock(io.github.excalibase.config.AppConfig)

        mockSchemaReflector = Mock(IDatabaseSchemaReflector)
        mockSchemaReflector.discoverComputedFields() >> [:]
        mockSchemaReflector.getCustomEnumTypes() >> []
        mockSchemaReflector.getCustomCompositeTypes() >> []

        mockServiceLookup.forBean(IDatabaseSchemaReflector.class, _) >> mockSchemaReflector

        generator = new PostgresGraphQLSchemaGeneratorImplement(mockServiceLookup, mockAppConfig)
        generator.setSchemaReflector(mockSchemaReflector)
    }
    
    def "should generate subscription schema with table change events"() {
        given: "a table with columns"
        Map<String, TableInfo> tables = [:]
        
        TableInfo customerTable = new TableInfo()
        customerTable.setName("Customer")
        customerTable.setColumns([
            createColumn("customer_id", "integer", false),
            createColumn("first_name", "varchar", true),
            createColumn("last_name", "varchar", true),
            createColumn("email", "varchar", true),
            createColumn("created_at", "timestamp", true)
        ])
        
        tables.put("Customer", customerTable)
        
        when: "generating GraphQL schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "subscription type should exist"
        schema.getSubscriptionType() != null

        and: "should have customer_changes subscription"
        GraphQLFieldDefinition customerChangesField = schema.getSubscriptionType().getFieldDefinition("customer_changes")
        customerChangesField != null
        
        and: "customer_changes should return CustomerChangeEvent type"
        GraphQLOutputType customerChangesType = customerChangesField.getType()
        customerChangesType instanceof GraphQLObjectType
        ((GraphQLObjectType) customerChangesType).getName() == "CustomerChangeEvent"
        
        and: "CustomerChangeEvent should have required fields"
        GraphQLObjectType changeEventType = (GraphQLObjectType) customerChangesType
        changeEventType.getFieldDefinition("operation") != null
        changeEventType.getFieldDefinition("table") != null
        changeEventType.getFieldDefinition("timestamp") != null
        changeEventType.getFieldDefinition("data") != null
        
        and: "data field should be CustomerSubscriptionData type"
        GraphQLOutputType dataFieldType = changeEventType.getFieldDefinition("data").getType()
        dataFieldType instanceof GraphQLObjectType
        ((GraphQLObjectType) dataFieldType).getName() == "CustomerSubscriptionData"
        
        and: "CustomerSubscriptionData should have all table columns"
        GraphQLObjectType subscriptionDataType = (GraphQLObjectType) dataFieldType
        subscriptionDataType.getFieldDefinition("customer_id") != null
        subscriptionDataType.getFieldDefinition("first_name") != null
        subscriptionDataType.getFieldDefinition("last_name") != null
        subscriptionDataType.getFieldDefinition("email") != null
        subscriptionDataType.getFieldDefinition("created_at") != null
        
        and: "should have old/new fields for updates"
        subscriptionDataType.getFieldDefinition("old") != null
        subscriptionDataType.getFieldDefinition("new") != null
    }
    
    def "should generate operation enum type for each table"() {
        given: "multiple tables"
        Map<String, TableInfo> tables = [:]
        
        TableInfo customerTable = new TableInfo()
        customerTable.setName("Customer")
        customerTable.setColumns([createColumn("id", "integer", false)])
        
        TableInfo orderTable = new TableInfo()
        orderTable.setName("Orders")
        orderTable.setColumns([createColumn("order_id", "integer", false)])
        
        tables.put("Customer", customerTable)
        tables.put("Orders", orderTable)
        
        when: "generating GraphQL schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "should have operation enums for each table"
        GraphQLType customerOperationType = schema.getType("CustomerChangeOperation")
        customerOperationType != null
        customerOperationType instanceof GraphQLEnumType
        
        GraphQLType ordersOperationType = schema.getType("OrdersChangeOperation")
        ordersOperationType != null
        ordersOperationType instanceof GraphQLEnumType
        
        and: "operation enums should have correct values"
        GraphQLEnumType customerEnum = (GraphQLEnumType) customerOperationType
        customerEnum.getValue("INSERT") != null
        customerEnum.getValue("UPDATE") != null
        customerEnum.getValue("DELETE") != null
        customerEnum.getValue("ERROR") != null
    }
    
    def "should handle tables with custom enum and composite types"() {
        given: "custom types"
        CustomEnumInfo statusEnum = new CustomEnumInfo()
        statusEnum.setName("status_enum")
        statusEnum.setValues(["active", "inactive", "pending"])
        
        CustomCompositeTypeInfo addressType = new CustomCompositeTypeInfo()
        addressType.setName("address_type")
        addressType.setAttributes([
            createCompositeAttribute("street", "varchar", 0),
            createCompositeAttribute("city", "varchar", 1)
        ])
        
        and: "table using custom types"
        TableInfo userTable = new TableInfo()
        userTable.setName("Users")
        userTable.setColumns([
            createColumn("user_id", "integer", false),
            createColumn("status", "status_enum", true),
            createColumn("address", "address_type", true)
        ])
        
        Map<String, TableInfo> tables = ["Users": userTable]
        
        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables, [statusEnum], [addressType])
        
        then: "subscription should work with custom types"
        GraphQLFieldDefinition userChangesField = schema.getSubscriptionType().getFieldDefinition("users_changes")
        userChangesField != null
        
        and: "subscription data should include custom typed fields"
        GraphQLObjectType changeEventType = (GraphQLObjectType) userChangesField.getType()
        GraphQLObjectType dataType = (GraphQLObjectType) changeEventType.getFieldDefinition("data").getType()
        dataType.getFieldDefinition("status") != null
        dataType.getFieldDefinition("address") != null
    }
    
    def "should handle empty tables gracefully"() {
        when: "generating schema with no tables"
        GraphQLSchema schema = generator.generateSchema([:], [], [])
        
        then: "should still have subscription type with health"
        schema.getSubscriptionType() != null
        schema.getSubscriptionType().getFieldDefinition("health") != null
        
        and: "should not have table subscription fields"
        schema.getSubscriptionType().getFieldDefinitions().size() == 1
    }
    
    def "should generate subscription types for view tables"() {
        given: "a view table"
        TableInfo customerView = new TableInfo()
        customerView.setName("CustomerView")
        customerView.setView(true)
        customerView.setColumns([
            createColumn("customer_id", "integer", false),
            createColumn("full_name", "varchar", true)
        ])
        
        Map<String, TableInfo> tables = ["CustomerView": customerView]
        
        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "should have subscription for view"
        GraphQLFieldDefinition viewChangesField = schema.getSubscriptionType().getFieldDefinition("customerview_changes")
        viewChangesField != null
        
        and: "view change event should have proper structure"
        GraphQLObjectType changeEventType = (GraphQLObjectType) viewChangesField.getType()
        changeEventType.getFieldDefinition("data") != null
    }
    
    def "subscription data types should have nullable fields"() {
        given: "table with non-nullable columns"
        TableInfo testTable = new TableInfo()
        testTable.setName("TestTable")
        testTable.setColumns([
            createColumn("id", "integer", false), // not nullable in table
            createColumn("name", "varchar", false) // not nullable in table
        ])
        
        Map<String, TableInfo> tables = ["TestTable": testTable]
        
        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "subscription data fields should be nullable"
        GraphQLFieldDefinition changesField = schema.getSubscriptionType().getFieldDefinition("testtable_changes")
        GraphQLObjectType changeEventType = (GraphQLObjectType) changesField.getType()
        GraphQLObjectType dataType = (GraphQLObjectType) changeEventType.getFieldDefinition("data").getType()
        
        // In subscription data, all fields should be nullable (not wrapped in GraphQLNonNull)
        GraphQLOutputType idFieldType = dataType.getFieldDefinition("id").getType()
        GraphQLOutputType nameFieldType = dataType.getFieldDefinition("name").getType()
        
        !(idFieldType instanceof GraphQLNonNull)
        !(nameFieldType instanceof GraphQLNonNull)
    }
    
    private ColumnInfo createColumn(String name, String type, boolean nullable) {
        ColumnInfo column = new ColumnInfo()
        column.setName(name)
        column.setType(type)
        column.setNullable(nullable)
        return column
    }
    
    private CompositeTypeAttribute createCompositeAttribute(String name, String type, int order) {
        CompositeTypeAttribute attribute = new CompositeTypeAttribute()
        attribute.setName(name)
        attribute.setType(type)
        attribute.setOrder(order)
        attribute.setNullable(true)
        return attribute
    }
}
