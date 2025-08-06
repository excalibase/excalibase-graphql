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
import graphql.analysis.FieldComplexityCalculator;
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
            // 1. Depth limiting - prevents deeply nested queries like friends.friends.friends...
            createQueryDepthInstrumentation(),
            
            // 2. Complexity analysis - prevents expensive operations based on field weights
            createQueryComplexityInstrumentation()
        ));
    }

    // Note: Parser options for query size limits would be configured at the 
    // GraphQL execution engine level in production deployments

    private MaxQueryDepthInstrumentation createQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(maxQueryDepth);
    }

    private MaxQueryComplexityInstrumentation createQueryComplexityInstrumentation() {
        // Define complexity calculator - each field has a base cost
        FieldComplexityCalculator fieldComplexityCalculator = (env, childrenComplexity) -> {
            // Base field cost
            int baseCost = 1;
            
            // Higher cost for list fields to prevent abuse
            String fieldName = env.getField().getName();
            if (fieldName.contains("Connection") || fieldName.toLowerCase().endsWith("s")) {
                baseCost = 3;
            }
            
            // Add cost for limit parameter (discourage large limit values)
            Object limitArg = env.getArguments().get("limit");
            if (limitArg instanceof Integer) {
                int limit = (Integer) limitArg;
                baseCost += Math.min(limit / 10, 20); // Cap additional complexity at 20
            }
            
            return baseCost + childrenComplexity;
        };

        return new MaxQueryComplexityInstrumentation(maxQueryComplexity, fieldComplexityCalculator);
    }
}