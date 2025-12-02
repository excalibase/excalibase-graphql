package io.github.excalibase.postgres.generator

import graphql.schema.GraphQLSchema
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector
import spock.lang.Specification
import spock.lang.Subject

/**
 * Test to debug subscription schema generation issues
 */
class SubscriptionSchemaGenerationTest extends Specification {

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
    
    def "should generate subscription types for simple table"() {
        given: "a simple table"
        Map<String, TableInfo> tables = [:]
        
        TableInfo testTable = new TableInfo()
        testTable.setName("TestTable")
        testTable.setColumns([
            createColumn("id", "integer", false),
            createColumn("name", "varchar", true)
        ])
        
        tables.put("TestTable", testTable)
        
        when: "generating GraphQL schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "schema should be created successfully"
        schema != null
        
        and: "subscription type should exist"
        schema.getSubscriptionType() != null
        
        and: "should have health field"
        schema.getSubscriptionType().getFieldDefinition("health") != null
        
        and: "should have table change subscription field"
        def changeField = schema.getSubscriptionType().getFieldDefinition("testtable_changes")
        changeField != null
        
        and: "TestTableChangeEvent type should exist in schema"
        schema.getType("TestTableChangeEvent") != null
        
        and: "TestTableSubscriptionData type should exist in schema"  
        schema.getType("TestTableSubscriptionData") != null
        
        and: "TestTableChangeOperation type should exist in schema"
        schema.getType("TestTableChangeOperation") != null
        
        when: "getting all type names"
        def typeNames = schema.getAllTypesAsList().collect { it.getName() }
        
        then: "should contain subscription types"
        typeNames.contains("TestTableChangeEvent")
        typeNames.contains("TestTableSubscriptionData")
        typeNames.contains("TestTableChangeOperation")
        
        and: "subscription type should have multiple fields"
        schema.getSubscriptionType().getFieldDefinitions().size() >= 2 // health + testtable_changes
    }
    
    def "debug subscription type creation process"() {
        given: "a simple table"
        Map<String, TableInfo> tables = [:]
        
        TableInfo debugTable = new TableInfo()
        debugTable.setName("DebugTable")
        debugTable.setColumns([createColumn("id", "integer", false)])
        
        tables.put("DebugTable", debugTable)
        
        when: "generating schema"
        GraphQLSchema schema = generator.generateSchema(tables, [], [])
        
        then: "debug the subscription type fields"
        def subscriptionType = schema.getSubscriptionType()
        subscriptionType != null
        
        def fieldNames = subscriptionType.getFieldDefinitions().collect { it.getName() }
        println "Subscription fields: ${fieldNames}"
        
        def allTypeNames = schema.getAllTypesAsList().collect { it.getName() }
        def subscriptionRelatedTypes = allTypeNames.findAll { 
            it.contains("Debug") || it.contains("Change") || it.contains("Operation") || it.contains("Subscription")
        }
        println "Subscription-related types: ${subscriptionRelatedTypes}"
        
        fieldNames.size() >= 2 // Should have health + debugtable_changes
    }
    
    private ColumnInfo createColumn(String name, String type, boolean nullable) {
        ColumnInfo column = new ColumnInfo()
        column.setName(name)
        column.setType(type)
        column.setNullable(nullable)
        return column
    }
}
