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
package io.github.excalibase.config;

import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.QueryComplexityInfo;
import graphql.analysis.FieldComplexityCalculator;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import java.util.function.Function;
import io.github.excalibase.observability.GraphQLObservabilityInstrumentation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * GraphQL Security Configuration for preventing DoS attacks through:
 * - Query depth limiting (prevent infinite nested queries)
 * - Query complexity analysis (prevent expensive operations)
 * - Parser limits (prevent oversized queries)
 */
@Configuration
public class GraphqlSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GraphqlSecurityConfig.class);

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public GraphqlSecurityConfig(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    // Security limits based on GraphQL.org recommendations and our API documentation
    @Value("${graphql.security.max-query-depth:10}")
    private int maxQueryDepth;

    @Value("${graphql.security.max-query-complexity:1000}")
    private int maxQueryComplexity;

    @Value("${graphql.security.max-query-tokens:5000}")
    private int maxQueryTokens;

    @Value("${graphql.security.max-query-characters:50000}")  // 50KB limit
    private int maxQueryCharacters;

    @Value("${graphql.security.max-whitespace-tokens:10000}")
    private int maxWhitespaceTokens;

    @Value("${graphql.security.max-rule-depth:50}")
    private int maxRuleDepth;

    /**
     * Creates chained instrumentation for GraphQL security controls.
     * Prevents common GraphQL DoS attack vectors.
     */
    @Bean
    public Instrumentation graphqlSecurityInstrumentation() {
        log.info("Configuring GraphQL security instrumentation - Max Depth: {}, Max Complexity: {}", 
                maxQueryDepth, maxQueryComplexity);

        return new ChainedInstrumentation(Arrays.asList(
            // 1. Observability — traces + metrics via OpenTelemetry (runs first to capture full latency)
            new GraphQLObservabilityInstrumentation(observationRegistry, meterRegistry),

            // 2. Depth limiting - prevents deeply nested queries like friends.friends.friends...
            createQueryDepthInstrumentation(),

            // 3. Complexity analysis - prevents expensive operations based on field weights
            createQueryComplexityInstrumentation()
        ));
    }

    // Note: Parser options for query size limits would be configured at the 
    // GraphQL execution engine level in production deployments

    private MaxQueryDepthInstrumentation createQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(maxQueryDepth);
    }

    // Fanout multiplier for list fields: approximates the number of rows each
    // list field will return (capped to avoid over-penalising large but fast queries).
    // Full MAX_ROWS (30) makes the 5-level circular query score ~2.6M — too strict.
    // A value of 10 gives ~3,000 for the same query, making 10,000 a sensible cap.
    private static final int COMPLEXITY_LIST_FANOUT = 10;

    private MaxQueryComplexityInstrumentation createQueryComplexityInstrumentation() {
        FieldComplexityCalculator fieldComplexityCalculator = (env, childrenComplexity) -> {
            // Skip introspection fields (__schema, __type, __typename)
            if (env.getField().getName().startsWith("__")) {
                return 0;
            }

            GraphQLType fieldType = GraphQLTypeUtil.unwrapNonNull(env.getFieldDefinition().getType());
            boolean isList = GraphQLTypeUtil.isList(fieldType);

            if (isList) {
                // Multiplicative: list cost grows with fanout × children so nested
                // lists (filmActors → actor → filmActors) score exponentially higher.
                int fanout = COMPLEXITY_LIST_FANOUT;
                Object limitArg = env.getArguments().get("limit");
                Object firstArg = env.getArguments().get("first");
                if (limitArg instanceof Integer l) fanout = Math.min(l, COMPLEXITY_LIST_FANOUT);
                else if (firstArg instanceof Integer f) fanout = Math.min(f, COMPLEXITY_LIST_FANOUT);
                return 1 + fanout * (childrenComplexity == 0 ? 1 : childrenComplexity);
            } else {
                // Scalar / single-row FK: additive, cheap
                return 1 + childrenComplexity;
            }
        };

        Function<QueryComplexityInfo, Boolean> complexityHandler = info -> {
            int score = info.getComplexity();
            boolean exceeded = score > maxQueryComplexity;
            if (exceeded) {
                log.warn("Query REJECTED — complexity {} exceeds max {}", score, maxQueryComplexity);
            } else {
                log.info("Query complexity: {} / {} ({}%)", score, maxQueryComplexity,
                        score * 100 / maxQueryComplexity);
            }
            return exceeded;
        };

        return new MaxQueryComplexityInstrumentation(maxQueryComplexity, fieldComplexityCalculator, complexityHandler);
    }
}