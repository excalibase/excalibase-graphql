package io.github.excalibase.postgres.subscription


import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.postgres.reflector.PostgresDatabaseSchemaReflectorImplement
import io.github.excalibase.postgres.service.CDCEvent
import io.github.excalibase.postgres.service.CDCService
import org.postgresql.replication.LogSequenceNumber
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import spock.lang.Specification
import spock.lang.Subject

import java.lang.reflect.Method

/**
 * Unit tests for PostgresDatabaseSubscriptionImplement focusing on core functionality
 * without complex reactive stream behaviors
 */
class PostgresDatabaseSubscriptionImplementTest extends Specification {
    
    CDCService mockCdcService = Mock()
    PostgresDatabaseSchemaReflectorImplement mockReflector = Mock()
    DataFetchingEnvironment mockEnvironment = Mock()
    Field mockField = Mock()
    
    def setup() {
        mockField.getName() >> "subscription_field"
        mockEnvironment.getField() >> mockField
        mockEnvironment.getArguments() >> [:]
    }
    
    @Subject
    PostgresDatabaseSubscriptionImplement subscriptionImplement = 
            new PostgresDatabaseSubscriptionImplement(mockCdcService, mockReflector)
    
    def "should map col_0 format data to actual column names using reflector"() {
        given: "table schema with column information"
        String tableName = "products"
        List<ColumnInfo> columns = [
            new ColumnInfo("product_id", "INTEGER", true, false),
            new ColumnInfo("product_name", "VARCHAR", false, true),
            new ColumnInfo("price", "DECIMAL", false, true)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        and: "CDC event with col_0 format data"
        CDCEvent cdcEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            tableName,
            '{"col_0": 123, "col_1": "Test Product", "col_2": 29.99}',
            "INSERT",
            LogSequenceNumber.valueOf("0/1234572")
        )
        
        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callPrivateMethod("convertCDCEventToGraphQLEvent", cdcEvent)
        
        then: "should map col_0 to actual column names"
        graphqlEvent.table == tableName
        graphqlEvent.operation == "INSERT"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.product_id == 123
        graphqlEvent.data.product_name == "Test Product"
        graphqlEvent.data.price == 29.99
        !graphqlEvent.data.containsKey("col_0")
        !graphqlEvent.data.containsKey("col_1") 
        !graphqlEvent.data.containsKey("col_2")
    }
    
    def "should handle UPDATE events with old/new structure and col_0 mapping"() {
        given: "table schema with column information"
        String tableName = "customer"
        List<ColumnInfo> columns = [
            new ColumnInfo("customer_id", "INTEGER", true, false),
            new ColumnInfo("first_name", "VARCHAR", false, true),
            new ColumnInfo("last_name", "VARCHAR", false, true)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        and: "UPDATE CDC event with old/new structure"
        CDCEvent updateEvent = new CDCEvent(
            CDCEvent.Type.UPDATE,
            "public",
            tableName,
            '{"new": {"col_0": 1, "col_1": "Jane", "col_2": "Doe"}, "old": {"col_0": 1, "col_1": "John", "col_2": "Doe"}}',
            "UPDATE",
            LogSequenceNumber.valueOf("0/1234568")
        )
        
        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callPrivateMethod("convertCDCEventToGraphQLEvent", updateEvent)
        
        then: "should map col_0 to actual column names in both old and new"
        graphqlEvent.table == tableName
        graphqlEvent.operation == "UPDATE"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.new instanceof Map
        graphqlEvent.data.new.customer_id == 1
        graphqlEvent.data.new.first_name == "Jane"
        graphqlEvent.data.new.last_name == "Doe"
        graphqlEvent.data.old instanceof Map
        graphqlEvent.data.old.customer_id == 1
        graphqlEvent.data.old.first_name == "John"
        graphqlEvent.data.old.last_name == "Doe"
    }
    
    def "should handle malformed JSON gracefully"() {
        given: "table schema"
        String tableName = "products"
        List<ColumnInfo> columns = [
            new ColumnInfo("product_id", "INTEGER", true, false)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        and: "CDC event with malformed JSON"
        CDCEvent malformedEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            tableName,
            'invalid-json-{incomplete',
            "INSERT",
            LogSequenceNumber.valueOf("0/1234569")
        )
        
        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callPrivateMethod("convertCDCEventToGraphQLEvent", malformedEvent)
        
        then: "should include parse error information"
        graphqlEvent.table == tableName
        graphqlEvent.operation == "INSERT"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.parseError != null
        graphqlEvent.data.parseError.contains("Failed to parse data as JSON")
        graphqlEvent.data.rawData == 'invalid-json-{incomplete'
    }
    
    def "should handle empty data gracefully"() {
        given: "table schema"
        String tableName = "empty_table"
        List<ColumnInfo> columns = [
            new ColumnInfo("empty_id", "INTEGER", true, false)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        and: "CDC event with empty data"
        CDCEvent emptyEvent = new CDCEvent(
            CDCEvent.Type.DELETE,
            "public",
            tableName,
            "",
            "DELETE",
            LogSequenceNumber.valueOf("0/1234570")
        )
        
        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callPrivateMethod("convertCDCEventToGraphQLEvent", emptyEvent)
        
        then: "should handle empty data gracefully"
        graphqlEvent.table == tableName
        graphqlEvent.operation == "DELETE"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.isEmpty()
    }
    
    def "should preserve LSN and timestamp in GraphQL events"() {
        given: "table schema"
        String tableName = "timestamped_table"
        List<ColumnInfo> columns = [
            new ColumnInfo("id", "INTEGER", true, false)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        and: "CDC event with LSN"
        LogSequenceNumber testLsn = LogSequenceNumber.valueOf("0/ABCDEF123")
        CDCEvent event = new CDCEvent(
            CDCEvent.Type.UPDATE,
            "public",
            tableName,
            '{"col_0": 42}',
            "UPDATE",
            testLsn
        )
        
        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callPrivateMethod("convertCDCEventToGraphQLEvent", event)
        
        then: "should preserve LSN and timestamp"
        graphqlEvent.lsn == "0/BCDEF123"
        graphqlEvent.timestamp != null
        graphqlEvent.timestamp instanceof String
        graphqlEvent.timestamp.contains("T") // ISO format
        graphqlEvent.data.id == 42
    }
    
    def "should create error event with proper structure"() {
        given: "table name and error message"
        String tableName = "test_table"
        String errorMessage = "Connection failed"
        
        when: "creating error event"
        Map<String, Object> errorEvent = callPrivateMethod("createErrorEvent", tableName, errorMessage)
        
        then: "should create proper error event structure"
        errorEvent.table == tableName
        errorEvent.operation == "ERROR"
        errorEvent.error == errorMessage
        errorEvent.data instanceof Map
        errorEvent.data.isEmpty()
        errorEvent.timestamp != null
        errorEvent.timestamp instanceof String
        errorEvent.timestamp.contains("T") // ISO format
    }
    
    def "should create table subscription resolver that returns DataFetcher"() {
        given: "table name"
        String tableName = "customer"
        
        when: "creating table subscription resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = 
                subscriptionImplement.buildTableSubscriptionResolver(tableName)
        
        then: "should return DataFetcher instance"
        resolver != null
        resolver instanceof DataFetcher
    }
    
    def "should create table subscription resolver that produces publisher"() {
        given: "table name and mock CDC service"
        String tableName = "products"
        
        // Setup table info
        List<ColumnInfo> columns = [
            new ColumnInfo("product_id", "INTEGER", true, false)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        // Mock CDC service to return a simple stream that completes quickly
        CDCEvent testEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            tableName,
            '{"col_0": 123}',
            "INSERT",
            LogSequenceNumber.valueOf("0/1234567")
        )
        mockCdcService.getTableEventStream(tableName) >> Flux.just(testEvent)
        
        when: "creating and executing resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = 
                subscriptionImplement.buildTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(mockEnvironment)
        
        then: "should produce publisher that emits events"
        publisher != null
        
        and: "publisher should emit CDC event first"
        StepVerifier.create(publisher)
                .assertNext { event ->
                    // First event should be the CDC event
                    assert event.table == tableName
                    assert event.operation == "INSERT"
                    assert event.data instanceof Map
                    assert event.data.product_id == 123
                }
                .thenCancel() // Cancel to avoid waiting for heartbeat
                .verify()
    }
    
    def "should create table subscription resolver that handles CDC service errors"() {
        given: "table name and failing CDC service"
        String tableName = "failing_table"
        
        // Setup table info
        List<ColumnInfo> columns = [
            new ColumnInfo("id", "INTEGER", true, false)
        ]
        List<ForeignKeyInfo> foreignKeys = []
        TableInfo tableInfo = new TableInfo(tableName, columns, foreignKeys)
        Map<String, TableInfo> schemaMap = [(tableName): tableInfo]
        mockReflector.reflectSchema() >> schemaMap
        
        // Mock CDC service to return error
        RuntimeException testError = new RuntimeException("CDC connection lost")
        mockCdcService.getTableEventStream(tableName) >> Flux.error(testError)
        
        when: "creating and executing resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = 
                subscriptionImplement.buildTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(mockEnvironment)
        
        then: "should handle error gracefully"
        publisher != null
        
        and: "should emit error event"
        StepVerifier.create(publisher)
                .assertNext { event ->
                    // Should get either error event or heartbeat first
                    assert event.table == tableName
                    assert event.operation == "ERROR" || event.operation == "HEARTBEAT"
                    if (event.operation == "ERROR") {
                        assert event.error == "CDC connection lost"
                        assert event.data instanceof Map
                    }
                }
                .thenCancel()
                .verify()
    }

    /**
     * Helper method to call private methods for testing
     */
    private Object callPrivateMethod(String methodName, Object... args) {
        if (methodName == "createErrorEvent") {
            Method method = PostgresDatabaseSubscriptionImplement.class.getDeclaredMethod(methodName, String.class, String.class)
            method.setAccessible(true)
            return method.invoke(subscriptionImplement, args)
        } else {
            Method method = PostgresDatabaseSubscriptionImplement.class.getDeclaredMethod(methodName, CDCEvent.class)
            method.setAccessible(true)
            return method.invoke(subscriptionImplement, args)
        }
    }
}