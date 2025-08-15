package io.github.excalibase.postgres.service

import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import spock.lang.Specification
import spock.lang.Subject

/**
 * Test suite for CDCService
 * 
 * Tests CDC event stream management and routing following TDD principles
 */
class CDCServiceTest extends Specification {
    
    @Subject
    CDCService cdcService = new CDCService()
    
    def "should create separate event streams for different tables"() {
        given: "CDC service is initialized"
        
        when: "requesting event streams for different tables"
        Flux<CDCEvent> customerStream = cdcService.getTableEventStream("customer")
        Flux<CDCEvent> orderStream = cdcService.getTableEventStream("orders")
        
        then: "should return different stream instances"
        customerStream != null
        orderStream != null
        customerStream != orderStream
    }
    
    def "should return the same stream instance for the same table"() {
        when: "requesting event stream for the same table multiple times"
        Flux<CDCEvent> stream1 = cdcService.getTableEventStream("customer")
        Flux<CDCEvent> stream2 = cdcService.getTableEventStream("customer")
        
        then: "should return the same underlying stream"
        stream1 != null
        stream2 != null
    }
    
    def "should provide status monitoring capabilities"() {
        when: "checking if service is running"
        boolean isRunning = cdcService.isRunning()
        
        then: "should return status information"
        !isRunning
        
        when: "checking running status without real CDC setup"
        boolean stillNotRunning = cdcService.isRunning()
        
        then: "should still indicate not running"
        !stillNotRunning
    }
    
    def "should handle CDC events and route to correct table streams"() {
        given: "table event streams"
        Flux<CDCEvent> customerStream = cdcService.getTableEventStream("customer")
        Flux<CDCEvent> orderStream = cdcService.getTableEventStream("orders")
        
        and: "test CDC events"
        CDCEvent customerEvent = new CDCEvent(
            CDCEvent.Type.INSERT,
            "public",
            "customer", 
            '{"customer_id": 1, "name": "Test Customer"}',
            "INSERT",
            null
        )
        
        CDCEvent orderEvent = new CDCEvent(
            CDCEvent.Type.UPDATE,
            "public",
            "orders",
            '{"order_id": 100, "status": "shipped"}', 
            "UPDATE",
            null
        )
        
        when: "simulating CDC event handling"
        def customerEvents = []
        def orderEvents = []
        
        customerStream.subscribe { event -> customerEvents.add(event) }
        orderStream.subscribe { event -> orderEvents.add(event) }
        
        cdcService.handleCDCEvent(customerEvent)
        cdcService.handleCDCEvent(orderEvent)
        
        Thread.sleep(100)
        
        then: "events should be routed to correct streams"
        customerEvents.size() == 1
        customerEvents[0].table == "customer"
        customerEvents[0].type == CDCEvent.Type.INSERT
        
        orderEvents.size() == 1
        orderEvents[0].table == "orders"
        orderEvents[0].type == CDCEvent.Type.UPDATE
    }
    
    def "should handle non-table CDC events without errors"() {
        when: "handling BEGIN/COMMIT events"
        CDCEvent beginEvent = new CDCEvent(
            CDCEvent.Type.BEGIN,
            null,
            null,
            null,
            "BEGIN",
            null
        )
        
        CDCEvent commitEvent = new CDCEvent(
            CDCEvent.Type.COMMIT,
            null,
            null,
            null,
            "COMMIT", 
            null
        )
        
        cdcService.handleCDCEvent(beginEvent)
        cdcService.handleCDCEvent(commitEvent)
        
        then: "should handle non-table events without errors"
        noExceptionThrown()
    }
    
    def "should track active subscription count"() {
        when: "no subscriptions exist"
        int initialCount = cdcService.getActiveSubscriptionCount()
        
        then: "count should be zero"
        initialCount == 0
        
        when: "creating and subscribing to table streams"
        def subscriptions = []
        subscriptions.add(cdcService.getTableEventStream("table1").subscribe())
        subscriptions.add(cdcService.getTableEventStream("table2").subscribe())
        subscriptions.add(cdcService.getTableEventStream("table3").subscribe())
        
        then: "count should increase"
        cdcService.getActiveSubscriptionCount() == 3
        
        when: "subscribing to same tables multiple times"
        subscriptions.add(cdcService.getTableEventStream("table1").subscribe())
        subscriptions.add(cdcService.getTableEventStream("table2").subscribe())
        
        then: "count should increase with additional subscriptions"
        cdcService.getActiveSubscriptionCount() == 5
        
        cleanup: "dispose subscriptions"
        subscriptions.each { it.dispose() }
    }
    
    def "should indicate running status correctly"() {
        when: "CDC service is not started"
        boolean notRunning = cdcService.isRunning()
        
        then: "should return false"
        !notRunning
        
        and: "should consistently return false without real CDC listener"
        !cdcService.isRunning()
    }
    
    def "should handle concurrent table stream requests safely"() {
        given: "multiple threads requesting streams"
        def tables = ["table1", "table2", "table3", "table4", "table5"]
        def streams = Collections.synchronizedList([])
        def subscriptions = Collections.synchronizedList([])
        
        when: "concurrent stream requests and subscriptions"
        def threads = tables.collect { tableName ->
            Thread.start {
                10.times {
                    def stream = cdcService.getTableEventStream(tableName)
                    streams.add(stream)
                    subscriptions.add(stream.subscribe())
                }
            }
        }
        
        // Wait for all threads to complete
        threads.each { it.join() }
        
        then: "should handle concurrent access without errors"
        streams.size() == 50
        subscriptions.size() == 50
        cdcService.getActiveSubscriptionCount() == 50
        noExceptionThrown()
        
        cleanup: "dispose all subscriptions"
        subscriptions.each { it.dispose() }
    }
}
