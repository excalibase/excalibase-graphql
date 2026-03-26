package io.github.excalibase.postgres.subscription


import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.github.excalibase.model.CDCEvent
import io.github.excalibase.service.NatsCDCService
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import spock.lang.Specification
import spock.lang.Subject

import java.lang.reflect.Method

/**
 * Unit tests for PostgresDatabaseSubscriptionImplement (NATS-backed).
 * Watcher sends real column names — no col_0 mapping needed.
 */
class PostgresDatabaseSubscriptionImplementTest extends Specification {

    NatsCDCService mockNatsCDCService = Mock()
    DataFetchingEnvironment mockEnvironment = Mock()
    Field mockField = Mock()

    def setup() {
        mockField.getName() >> "subscription_field"
        mockEnvironment.getField() >> mockField
        mockEnvironment.getArguments() >> [:]
    }

    @Subject
    PostgresDatabaseSubscriptionImplement subscriptionImplement =
            new PostgresDatabaseSubscriptionImplement(mockNatsCDCService)

    def "should map watcher event with real column names directly"() {
        given: "CDC event from watcher with real column names"
        CDCEvent cdcEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            "products",
            '{"product_id": 123, "product_name": "Test Product", "price": 29.99}',
            "INSERT",
            null,
            System.currentTimeMillis(),
            0L
        )

        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callToGraphQLEvent(cdcEvent)

        then: "should pass through real column names directly"
        graphqlEvent.table == "products"
        graphqlEvent.operation == "INSERT"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.product_id == 123
        graphqlEvent.data.product_name == "Test Product"
        graphqlEvent.data.price == 29.99
    }

    def "should handle UPDATE events with old/new structure"() {
        given: "UPDATE CDC event from watcher"
        CDCEvent updateEvent = new CDCEvent(
            CDCEvent.Type.UPDATE,
            "public",
            "customer",
            '{"new": {"customer_id": 1, "first_name": "Jane", "last_name": "Doe"}, "old": {"customer_id": 1, "first_name": "John", "last_name": "Doe"}}',
            "UPDATE",
            null,
            System.currentTimeMillis(),
            0L
        )

        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callToGraphQLEvent(updateEvent)

        then: "should preserve old/new structure with real column names"
        graphqlEvent.table == "customer"
        graphqlEvent.operation == "UPDATE"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.new instanceof Map
        graphqlEvent.data.new.customer_id == 1
        graphqlEvent.data.new.first_name == "Jane"
        graphqlEvent.data.old instanceof Map
        graphqlEvent.data.old.first_name == "John"
    }

    def "should handle malformed JSON gracefully"() {
        given: "CDC event with malformed JSON"
        CDCEvent malformedEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            "products",
            'invalid-json-{incomplete',
            "INSERT",
            null,
            System.currentTimeMillis(),
            0L
        )

        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callToGraphQLEvent(malformedEvent)

        then: "should return empty data map (graceful handling)"
        graphqlEvent.table == "products"
        graphqlEvent.operation == "INSERT"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.isEmpty()
    }

    def "should handle empty data gracefully"() {
        given: "CDC event with empty data"
        CDCEvent emptyEvent = new CDCEvent(
            CDCEvent.Type.DELETE,
            "public",
            "empty_table",
            "",
            "DELETE",
            null,
            System.currentTimeMillis(),
            0L
        )

        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callToGraphQLEvent(emptyEvent)

        then: "should handle empty data gracefully"
        graphqlEvent.table == "empty_table"
        graphqlEvent.operation == "DELETE"
        graphqlEvent.data instanceof Map
        graphqlEvent.data.isEmpty()
    }

    def "should include timestamp in GraphQL events"() {
        given: "CDC event with timestamp"
        long testTimestamp = 1742056200000L
        CDCEvent event = new CDCEvent(
            CDCEvent.Type.UPDATE,
            "public",
            "timestamped_table",
            '{"id": 42}',
            "UPDATE",
            null,
            testTimestamp,
            0L
        )

        when: "converting CDC event to GraphQL event"
        Map<String, Object> graphqlEvent = callToGraphQLEvent(event)

        then: "should include timestamp in ISO format"
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
        Map<String, Object> errorEvent = callCreateErrorEvent(tableName, errorMessage)

        then: "should create proper error event structure"
        errorEvent.table == tableName
        errorEvent.operation == "ERROR"
        errorEvent.error == errorMessage
        errorEvent.data instanceof Map
        errorEvent.data.isEmpty()
        errorEvent.timestamp != null
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
        given: "table name and mock NATS CDC service"
        String tableName = "products"

        CDCEvent testEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            tableName,
            '{"product_id": 123}',
            "INSERT",
            null,
            System.currentTimeMillis(),
            0L
        )
        mockNatsCDCService.getTableEventStream(tableName) >> Flux.just(testEvent)

        when: "creating and executing resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver =
                subscriptionImplement.buildTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(mockEnvironment)

        then: "should produce publisher that emits events"
        publisher != null

        and: "publisher should emit CDC event"
        StepVerifier.create(publisher)
                .assertNext { event ->
                    assert event.table == tableName
                    assert event.operation == "INSERT"
                    assert event.data instanceof Map
                    assert event.data.product_id == 123
                }
                .thenCancel()
                .verify()
    }

    def "should create table subscription resolver that handles errors"() {
        given: "table name and failing NATS CDC service"
        String tableName = "failing_table"

        RuntimeException testError = new RuntimeException("NATS connection lost")
        mockNatsCDCService.getTableEventStream(tableName) >> Flux.error(testError)

        when: "creating and executing resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver =
                subscriptionImplement.buildTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(mockEnvironment)

        then: "should handle error gracefully"
        publisher != null

        and: "should emit error event or heartbeat"
        StepVerifier.create(publisher)
                .assertNext { event ->
                    assert event.table == tableName
                    assert event.operation == "ERROR" || event.operation == "HEARTBEAT"
                    if (event.operation == "ERROR") {
                        assert event.error == "NATS connection lost"
                        assert event.data instanceof Map
                    }
                }
                .thenCancel()
                .verify()
    }

    private Map<String, Object> callToGraphQLEvent(CDCEvent event) {
        Method method = PostgresDatabaseSubscriptionImplement.class.getDeclaredMethod("toGraphQLEvent", CDCEvent.class)
        method.setAccessible(true)
        return method.invoke(subscriptionImplement, event)
    }

    private Map<String, Object> callCreateErrorEvent(String tableName, String errorMessage) {
        Method method = PostgresDatabaseSubscriptionImplement.class.getDeclaredMethod("createErrorEvent", String.class, String.class)
        method.setAccessible(true)
        return method.invoke(subscriptionImplement, tableName, errorMessage)
    }
}
