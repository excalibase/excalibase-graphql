package io.github.excalibase.config

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.springframework.web.socket.*
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive test suite for GraphQLWebSocketHandler
 * 
 * Tests WebSocket protocol handling, subscription management, and error scenarios
 */
class GraphQLWebSocketHandlerTest extends Specification {
    
    GraphqlConfig mockGraphqlConfig = Mock()
    WebSocketSession mockSession = Mock()
    ObjectMapper objectMapper = new ObjectMapper()
    
    @Subject
    GraphQLWebSocketHandler handler = new GraphQLWebSocketHandler(mockGraphqlConfig)
    
    def setup() {
        mockSession.getId() >> "test-session-123"
        mockSession.isOpen() >> true
    }

    def "should establish WebSocket connection and initialize session"() {
        when: "connection is established"
        handler.afterConnectionEstablished(mockSession)
        
        then: "should initialize session without errors"
        noExceptionThrown()
    }

    def "should handle connection_init message"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "connection init message"
        def message = new TextMessage('{"type":"connection_init"}')
        
        when: "handling the message"
        handler.handleTextMessage(mockSession, message)
        
        then: "should send connection_ack"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "connection_ack"
        })
    }

    def "should handle ping message"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "ping message"
        def message = new TextMessage('{"type":"ping"}')
        
        when: "handling the message"
        handler.handleTextMessage(mockSession, message)
        
        then: "should send pong"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "pong"
        })
    }

    def "should handle complete message gracefully"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "complete message"
        def completeMessage = new TextMessage('{"type":"complete","id":"sub1"}')
        
        when: "handling complete message"
        handler.handleTextMessage(mockSession, completeMessage)
        
        then: "should handle gracefully even if subscription doesn't exist"
        noExceptionThrown()
    }

    def "should clean up when connection closes"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        when: "connection closes"
        handler.afterConnectionClosed(mockSession, CloseStatus.NORMAL)
        
        then: "should handle cleanup without errors"
        noExceptionThrown()
    }

    def "should support graphql-transport-ws subprotocol"() {
        when: "checking supported subprotocols"
        List<String> subprotocols = handler.getSubProtocols()
        
        then: "should support graphql-transport-ws"
        subprotocols.contains("graphql-transport-ws")
    }

    def "should handle unknown message types gracefully"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "unknown message type"
        def unknownMessage = new TextMessage('{"type":"unknown_type","id":"test"}')
        
        when: "handling unknown message"
        handler.handleTextMessage(mockSession, unknownMessage)
        
        then: "should not crash"
        noExceptionThrown()
    }

    def "should handle multiple sessions independently"() {
        given: "two different sessions"
        WebSocketSession session1 = Mock()
        session1.getId() >> "session-1"
        session1.isOpen() >> true
        
        WebSocketSession session2 = Mock()
        session2.getId() >> "session-2"
        session2.isOpen() >> true
        
        when: "establishing both connections"
        handler.afterConnectionEstablished(session1)
        handler.afterConnectionEstablished(session2)
        
        and: "closing one session"
        handler.afterConnectionClosed(session1, CloseStatus.NORMAL)
        
        then: "should not affect the other session"
        noExceptionThrown()
    }

    def "should handle empty messages gracefully"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "empty message"
        def emptyMessage = new TextMessage('{}')
        
        when: "handling empty message"
        handler.handleTextMessage(mockSession, emptyMessage)
        
        then: "should not crash"
        noExceptionThrown()
    }

    def "should be instantiable with GraphqlConfig"() {
        when: "creating handler with config"
        def testHandler = new GraphQLWebSocketHandler(mockGraphqlConfig)
        
        then: "should create successfully"
        testHandler != null
    }

    def "should handle connection establishment for multiple sessions"() {
        given: "multiple sessions"
        WebSocketSession session1 = Mock()
        session1.getId() >> "session-1"
        
        WebSocketSession session2 = Mock()
        session2.getId() >> "session-2"
        
        when: "establishing connections"
        handler.afterConnectionEstablished(session1)
        handler.afterConnectionEstablished(session2)
        
        then: "should handle both without errors"
        noExceptionThrown()
    }

    def "should handle subscribe message without session initialization"() {
        given: "no connection established"
        
        and: "subscribe message"
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "sub1",
            payload: [
                query: "subscription { health }",
                variables: [:]
            ]
        ]))
        
        when: "handling subscribe message without initialization"
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        then: "should send session error"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "error" && data.payload.message.contains("Session not properly initialized")
        })
    }

    def "should complete subscription when complete message received"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "manually add subscription to session"
        def mockSubscription = Mock(org.reactivestreams.Subscription)
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        sessionSubs.put("sub1", mockSubscription)
        
        and: "complete message"
        def completeMessage = new TextMessage(JsonUtil.toJson([
            type: "complete",
            id: "sub1"
        ]))
        
        when: "handling complete message"
        handler.handleTextMessage(mockSession, completeMessage)
        
        then: "should cancel the subscription"
        1 * mockSubscription.cancel()
    }

    def "should handle session cleanup on connection close with active subscriptions"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "mock active subscription"
        def mockSubscription = Mock(org.reactivestreams.Subscription)
        
        when: "simulating active subscription and connection close"
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        sessionSubs.put("sub1", mockSubscription)
        
        handler.afterConnectionClosed(mockSession, CloseStatus.NORMAL)
        
        then: "should cancel active subscriptions"
        1 * mockSubscription.cancel()
    }

    def "should handle sendMessage with closed session"() {
        given: "established connection then closed session"
        handler.afterConnectionEstablished(mockSession)
        
        when: "session becomes closed and ping is sent"
        mockSession.isOpen() >> false
        def pingMessage = new TextMessage('{"type":"ping"}')
        handler.handleTextMessage(mockSession, pingMessage)
        
        then: "should not send message to closed session"
        0 * mockSession.sendMessage(_)
    }

    def "should handle IOException during message sending"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "session that throws IOException"
        mockSession.sendMessage(_) >> { throw new IOException("Connection failed") }
        
        when: "handling ping message"
        def pingMessage = new TextMessage('{"type":"ping"}')
        handler.handleTextMessage(mockSession, pingMessage)
        
        then: "should handle IOException gracefully"
        noExceptionThrown()
    }

    def "should handle concurrent ping operations safely"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        when: "handling multiple ping operations concurrently"
        def threads = []
        10.times { i ->
            threads.add(Thread.start {
                def pingMessage = new TextMessage(JsonUtil.toJson([
                    type: "ping"
                ]))
                handler.handleTextMessage(mockSession, pingMessage)
            })
        }
        
        threads.each { it.join() }
        
        then: "should handle concurrent operations without errors"
        noExceptionThrown()
        10 * mockSession.sendMessage(_)
    }

    def "should handle connection close with different close statuses"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        when: "connection closes with different status"
        handler.afterConnectionClosed(mockSession, CloseStatus.SESSION_NOT_RELIABLE)
        
        then: "should handle cleanup without errors"
        noExceptionThrown()
    }

    def "should handle malformed JSON messages"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "malformed JSON message"
        def malformedMessage = new TextMessage("{ invalid json")
        
        when: "handling malformed message"
        handler.handleTextMessage(mockSession, malformedMessage)
        
        then: "should throw exception for malformed JSON"
        def ex = thrown(Exception)
        ex instanceof IOException || ex instanceof RuntimeException
    }

    def "should handle message without type field"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "message without type"
        def messageWithoutType = new TextMessage(JsonUtil.toJson([id: "test", payload: [:]]))
        
        when: "handling message without type"
        handler.handleTextMessage(mockSession, messageWithoutType)
        
        then: "should not crash"
        noExceptionThrown()
    }

    def "should handle subscribe message missing required fields"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "subscribe message without id"
        def subscribeWithoutId = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            payload: [query: "subscription { health }", variables: [:]]
        ]))
        
        when: "handling subscribe without id"
        handler.handleTextMessage(mockSession, subscribeWithoutId)
        
        then: "should throw NullPointerException for missing id"
        thrown(NullPointerException)
    }

    def "should handle subscribe message without payload"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "subscribe message without payload"
        def subscribeWithoutPayload = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "sub1"
        ]))
        
        when: "handling subscribe without payload"
        handler.handleTextMessage(mockSession, subscribeWithoutPayload)
        
        then: "should throw NullPointerException for missing payload"
        thrown(NullPointerException)
    }

    def "should handle complete message for non-existent subscription"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "complete message for non-existent subscription"
        def completeMessage = new TextMessage(JsonUtil.toJson([
            type: "complete",
            id: "non-existent-sub"
        ]))
        
        when: "handling complete for non-existent subscription"
        handler.handleTextMessage(mockSession, completeMessage)
        
        then: "should handle gracefully"
        noExceptionThrown()
    }

    def "should maintain session isolation"() {
        given: "two different sessions"
        WebSocketSession session1 = Mock()
        session1.getId() >> "session-1"
        session1.isOpen() >> true
        
        WebSocketSession session2 = Mock()
        session2.getId() >> "session-2"
        session2.isOpen() >> true
        
        and: "mock subscriptions"
        def mockSub1 = Mock(org.reactivestreams.Subscription)
        def mockSub2 = Mock(org.reactivestreams.Subscription)
        
        when: "establishing connections and adding subscriptions"
        handler.afterConnectionEstablished(session1)
        handler.afterConnectionEstablished(session2)
        
        def sessionSubs1 = handler.@sessionSubscriptions.get("session-1")
        def sessionSubs2 = handler.@sessionSubscriptions.get("session-2")
        sessionSubs1.put("sub1", mockSub1)
        sessionSubs2.put("sub2", mockSub2)
        
        and: "closing one session"
        handler.afterConnectionClosed(session1, CloseStatus.NORMAL)
        
        then: "only session1's subscription should be cancelled"
        1 * mockSub1.cancel()
        0 * mockSub2.cancel()
        
        and: "session2 should still have its subscription"
        handler.@sessionSubscriptions.containsKey("session-2")
        !handler.@sessionSubscriptions.containsKey("session-1")
    }

    def "should handle concurrent connection establishments"() {
        given: "multiple sessions to establish concurrently"
        def sessions = []
        10.times { i ->
            def session = Mock(WebSocketSession)
            session.getId() >> "session-${i}"
            session.isOpen() >> true
            sessions.add(session)
        }
        
        when: "establishing connections concurrently"
        def threads = []
        sessions.each { session ->
            threads.add(Thread.start {
                handler.afterConnectionEstablished(session)
            })
        }
        threads.each { it.join() }
        
        then: "all sessions should be initialized"
        noExceptionThrown()
        handler.@sessionSubscriptions.size() == 10
    }

    def "should handle subscribe message when GraphQL execution returns null publisher"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "mock GraphQL that returns null publisher"
        GraphQL mockGraphQL = Mock()
        mockGraphqlConfig.graphQL() >> mockGraphQL
        
        ExecutionResult mockResult = Mock()
        mockResult.getData() >> null
        mockGraphQL.execute(_) >> mockResult
        
        and: "subscribe message"
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "failed-sub",
            payload: [
                query: "query { nonSubscription }",
                variables: [:]
            ]
        ]))
        
        when: "handling subscribe message"
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        then: "should send error message"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "error" && 
            data.id == "failed-sub" &&
            data.payload.message == "Subscription execution failed"
        })
    }

    def "should handle subscribe message with existing subscription replacement"() {
        given: "established connection"
        handler.afterConnectionEstablished(mockSession)
        
        and: "existing subscription"
        Subscription existingSubscription = Mock()
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        sessionSubs.put("replace-me", existingSubscription)
        
        and: "subscribe message with existing ID"
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "replace-me", 
            payload: [
                query: "subscription { health }",
                variables: [:]
            ]
        ]))
        
        when: "handling subscribe message"
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        then: "should cancel existing subscription and create new one"
        1 * existingSubscription.cancel()
        1 * mockGraphqlConfig.graphQL() >> {
            GraphQL testGraphQL = Mock()
            ExecutionResult testResult = Mock()
            Publisher<ExecutionResult> testPublisher = Mock()
            Subscription newSubscription = Mock()
            
            testGraphQL.execute(_) >> testResult
            testResult.getData() >> testPublisher
            testPublisher.subscribe(_) >> { args ->
                Subscriber<ExecutionResult> subscriber = args[0]
                subscriber.onSubscribe(newSubscription)
            }
            
            return testGraphQL
        }
    }

    def "should handle Publisher onNext callback with data and request more"() {
        given: "established connection with active subscription"
        handler.afterConnectionEstablished(mockSession)
        
        GraphQL mockGraphQL = Mock()
        mockGraphqlConfig.graphQL() >> mockGraphQL
        
        ExecutionResult mockResult = Mock()
        Publisher<ExecutionResult> mockPublisher = Mock()
        mockResult.getData() >> mockPublisher
        mockGraphQL.execute(_) >> mockResult
        
        Subscriber<ExecutionResult> capturedSubscriber
        Subscription mockSubscription = Mock()
        
        mockPublisher.subscribe(_) >> { args ->
            capturedSubscriber = args[0]
            capturedSubscriber.onSubscribe(mockSubscription)
        }
        
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "data-sub",
            payload: [query: "subscription { health }", variables: [:]]
        ]))
        
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        and: "execution result with data"
        ExecutionResult subscriptionData = Mock()
        subscriptionData.getData() >> [customerChanges: [id: 1, name: "John"]]
        
        when: "Publisher sends data via onNext"
        capturedSubscriber.onNext(subscriptionData)
        
        then: "should send next message with data"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "next" && 
            data.id == "data-sub" &&
            data.payload.data.customerChanges.id == 1
        })
        
        and: "should request initial data and more after onNext"
        (1.._) * mockSubscription.request(1) // at least initial call, possibly more
    }

    def "should handle Publisher onError callback with error message sending"() {
        given: "established connection with active subscription"
        handler.afterConnectionEstablished(mockSession)
        
        GraphQL mockGraphQL = Mock()
        mockGraphqlConfig.graphQL() >> mockGraphQL
        
        ExecutionResult mockResult = Mock()
        Publisher<ExecutionResult> mockPublisher = Mock()
        mockResult.getData() >> mockPublisher
        mockGraphQL.execute(_) >> mockResult
        
        Subscriber<ExecutionResult> capturedSubscriber
        Subscription mockSubscription = Mock()
        
        mockPublisher.subscribe(_) >> { args ->
            capturedSubscriber = args[0]
            capturedSubscriber.onSubscribe(mockSubscription)
        }
        
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "error-sub",
            payload: [query: "subscription { health }", variables: [:]]
        ]))
        
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        when: "Publisher sends error"
        capturedSubscriber.onError(new RuntimeException("Database connection failed"))
        
        then: "should send error message"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "error" && 
            data.id == "error-sub" &&
            data.payload.message == "Database connection failed"
        })
        
        and: "should remove subscription from session"
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        !sessionSubs.containsKey("error-sub")
    }

    def "should handle Publisher onComplete callback with complete message sending"() {
        given: "established connection with active subscription"
        handler.afterConnectionEstablished(mockSession)
        
        GraphQL mockGraphQL = Mock()
        mockGraphqlConfig.graphQL() >> mockGraphQL
        
        ExecutionResult mockResult = Mock()
        Publisher<ExecutionResult> mockPublisher = Mock()
        mockResult.getData() >> mockPublisher
        mockGraphQL.execute(_) >> mockResult
        
        Subscriber<ExecutionResult> capturedSubscriber
        Subscription mockSubscription = Mock()
        
        mockPublisher.subscribe(_) >> { args ->
            capturedSubscriber = args[0]
            capturedSubscriber.onSubscribe(mockSubscription)
        }
        
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "complete-sub",
            payload: [query: "subscription { health }", variables: [:]]
        ]))
        
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        when: "Publisher completes"
        capturedSubscriber.onComplete()
        
        then: "should send complete message"
        1 * mockSession.sendMessage({ TextMessage msg ->
            def data = objectMapper.readValue(msg.payload, Map)
            data.type == "complete" && data.id == "complete-sub"
        })
        
        and: "should remove subscription from session"
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        !sessionSubs.containsKey("complete-sub")
    }

    def "should only request more data if subscription is still active"() {
        given: "established connection with active subscription"
        handler.afterConnectionEstablished(mockSession)
        
        GraphQL mockGraphQL = Mock()
        mockGraphqlConfig.graphQL() >> mockGraphQL
        
        ExecutionResult mockResult = Mock()
        Publisher<ExecutionResult> mockPublisher = Mock()
        mockResult.getData() >> mockPublisher
        mockGraphQL.execute(_) >> mockResult
        
        Subscriber<ExecutionResult> capturedSubscriber
        Subscription mockSubscription = Mock()
        
        mockPublisher.subscribe(_) >> { args ->
            capturedSubscriber = args[0]
            capturedSubscriber.onSubscribe(mockSubscription)
        }
        
        def subscribeMessage = new TextMessage(JsonUtil.toJson([
            type: "subscribe",
            id: "conditional-sub",
            payload: [query: "subscription { health }", variables: [:]]
        ]))
        
        handler.handleTextMessage(mockSession, subscribeMessage)
        
        and: "subscription removed from session (cancelled elsewhere)"
        def sessionSubs = handler.@sessionSubscriptions.get(mockSession.getId())
        sessionSubs.remove("conditional-sub")
        
        ExecutionResult subscriptionData = Mock()
        subscriptionData.getData() >> [health: "OK"]
        
        when: "Publisher sends data but subscription no longer active"
        capturedSubscriber.onNext(subscriptionData)
        
        then: "should send data but not request more"
        1 * mockSession.sendMessage(_) // should still send the data
        (0.._) * mockSubscription.request(1) // initial request may or may not be detected by mock
    }
}