/*
 * Copyright 2025 Excalibase Team and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.excalibase.postgres.subscription

import graphql.schema.DataFetcher
import io.github.excalibase.config.AppConfig
import io.github.excalibase.constant.DatabaseType
import io.github.excalibase.service.ServiceLookup
import org.reactivestreams.Publisher
import org.springframework.jdbc.core.JdbcTemplate
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import spock.lang.Specification

import java.time.Duration

/**
 * Test for PostgresDatabaseSubscriptionImplement following TDD methodology.
 */
class PostgresDatabaseSubscriptionImplementTest extends Specification {
    PostgresDatabaseSubscriptionImplement subscriptionService

    def setup() {
        
        subscriptionService = new PostgresDatabaseSubscriptionImplement()
    }

    def "should create table subscription resolver for customer table"() {
        given: "a table name"
        String tableName = "customer"

        when: "creating table subscription resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = subscriptionService.createTableSubscriptionResolver(tableName)

        then: "resolver should not be null"
        resolver != null

        when: "executing the resolver"
        Publisher<Map<String, Object>> publisher = resolver.get(null)

        then: "publisher should emit periodic updates"
        publisher != null
        publisher instanceof Flux

        and: "should emit data with table information"
        StepVerifier.create(publisher.take(2))
                .expectNextMatches { Map<String, Object> data ->
                    data.containsKey("table") &&
                    data.get("table") == "customer" &&
                    data.containsKey("operation") &&
                    data.containsKey("timestamp") &&
                    data.containsKey("data") &&
                    data.get("data") instanceof Map
                }
                .expectNextMatches { Map<String, Object> data ->
                    data.get("table") == "customer"
                }
                .verifyComplete()
    }

    def "should create table subscription resolver for product table with specific data"() {
        given: "a product table name"
        String tableName = "product"

        when: "creating table subscription resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = subscriptionService.createTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(null)

        then: "should emit product-specific placeholder data"
        StepVerifier.create(publisher.take(1))
                .expectNextMatches { Map<String, Object> data ->
                    Map<String, Object> productData = data.get("data") as Map<String, Object>
                    productData.containsKey("product_id") &&
                    productData.containsKey("product_name") &&
                    productData.containsKey("price") &&
                    productData.get("product_name").toString().startsWith("Product")
                }
                .verifyComplete()
    }

    def "should create health subscription resolver"() {
        when: "creating health subscription resolver"
        DataFetcher<Publisher<String>> resolver = subscriptionService.createHealthSubscriptionResolver()

        then: "resolver should not be null"
        resolver != null

        when: "executing the resolver"
        Publisher<String> publisher = resolver.get(null)

        then: "publisher should emit health messages"
        publisher != null
        publisher instanceof Flux

        and: "should emit health status messages"
        StepVerifier.create(publisher.take(2))
                .expectNextMatches { String message ->
                    message.startsWith("OK - heartbeat")
                }
                .expectNextMatches { String message ->
                    message.startsWith("OK - heartbeat")
                }
                .verifyComplete()
    }

    def "should handle generic table names with default placeholder data"() {
        given: "a generic table name"
        String tableName = "unknown_table"

        when: "creating table subscription resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = subscriptionService.createTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(null)

        then: "should emit generic placeholder data"
        StepVerifier.create(publisher.take(1))
                .expectNextMatches { Map<String, Object> data ->
                    Map<String, Object> tableData = data.get("data") as Map<String, Object>
                    tableData.containsKey("name") &&
                    tableData.containsKey("value") &&
                    tableData.get("name").toString().contains("unknown_table")
                }
                .verifyComplete()
    }

    def "should emit updates at correct intervals for table subscription"() {
        given: "a table name"
        String tableName = "customer"

        when: "creating table subscription resolver"
        DataFetcher<Publisher<Map<String, Object>>> resolver = subscriptionService.createTableSubscriptionResolver(tableName)
        Publisher<Map<String, Object>> publisher = resolver.get(null)

        then: "should emit updates approximately every second"
        StepVerifier.create(publisher.take(2))
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofSeconds(5))
    }
}
