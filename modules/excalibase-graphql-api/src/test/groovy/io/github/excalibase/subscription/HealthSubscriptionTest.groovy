package io.github.excalibase.subscription

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import io.github.excalibase.config.GraphqlConfig

@SpringBootTest
@ActiveProfiles("test")
class HealthSubscriptionTest extends Specification {

    @Autowired
    GraphqlConfig graphqlConfig

    def "should expose health subscription and receive heartbeat event"() {
        given: "GraphQL instance with subscription schema"
        GraphQL graphQL = graphqlConfig.graphQL()

        and: "a health subscription query"
        String query = """
        subscription { 
            health 
        }
        """

        when: "executing subscription"
        ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
            .query(query)
            .build())

        then: "result data should be a Publisher"
        result.getData() instanceof Publisher

        when: "subscribing and awaiting first event"
        Publisher<ExecutionResult> publisher = (Publisher<ExecutionResult>) result.getData()
        CountDownLatch latch = new CountDownLatch(1)
        AtomicReference<Object> firstEventData = new AtomicReference<>()
        AtomicReference<Throwable> errorRef = new AtomicReference<>()

        publisher.subscribe(new Subscriber<ExecutionResult>() {
            Subscription s
            @Override
            void onSubscribe(Subscription s) {
                this.s = s
                s.request(1)
            }

            @Override
            void onNext(ExecutionResult er) {
                firstEventData.set(er.getData())
                latch.countDown()
                s.request(1)
            }

            @Override
            void onError(Throwable t) { errorRef.set(t); latch.countDown() }

            @Override
            void onComplete() {}
        })

        then: "should receive at least one heartbeat event quickly"
        latch.await(3, TimeUnit.SECONDS)
        errorRef.get() == null

        and: "event payload should include health information"
        def event = firstEventData.get()
        // GraphQL Java returns a Map for subscription payload like { health: "..." }
        if (event instanceof Map) {
            assert ((Map) event).containsKey("health")
            assert ((Map) event).get("health").toString().toLowerCase().contains("ok")
        } else {
            // Fallback if direct scalar is returned
            assert event != null
            assert event.toString().toLowerCase().contains("ok")
        }
    }
}


