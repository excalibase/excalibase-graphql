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

import io.github.excalibase.constant.DatabaseType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application configuration properties.
 */
@ConfigurationProperties(prefix = "app")
@Component
public class AppConfig {
    
    /** The database schema to include in GraphQL schema generation */
    private String allowedSchema;
    
    /** The type of database being used */
    private DatabaseType databaseType;
    
    /** Security configuration */
    private SecurityConfig security = new SecurityConfig();
    
    /** Cache configuration */
    private CacheConfig cache = new CacheConfig();

    public String getAllowedSchema() {
        return allowedSchema;
    }

    public void setAllowedSchema(String allowedSchema) {
        this.allowedSchema = allowedSchema;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }

    /**
     * Security configuration properties.
     */
    public static class SecurityConfig {

        /** Enable role-based schema filtering and RLS/CLS support */
        private boolean roleBasedSchema = true;

        /** Enable user context for RLS (Row Level Security) policies */
        private boolean userContextEnabled = true;

        /**
         * User ID extractor type: "header" (default) or "jwt"
         *
         * header: reads X-User-Id header (and X-Claim-* for extra claims)
         * jwt:    decodes Authorization: Bearer <token>, uses "sub" as user_id
         *         and all other claims as request.jwt.<claim>
         */
        private String userIdExtractorType = "header";

        /** Header name for user ID when using "header" extractor (default: X-User-Id) */
        private String userIdHeader = "X-User-Id";

        public boolean isRoleBasedSchema() {
            return roleBasedSchema;
        }

        public void setRoleBasedSchema(boolean roleBasedSchema) {
            this.roleBasedSchema = roleBasedSchema;
        }

        public boolean isUserContextEnabled() {
            return userContextEnabled;
        }

        public void setUserContextEnabled(boolean userContextEnabled) {
            this.userContextEnabled = userContextEnabled;
        }

        public String getUserIdExtractorType() {
            return userIdExtractorType;
        }

        public void setUserIdExtractorType(String userIdExtractorType) {
            this.userIdExtractorType = userIdExtractorType;
        }

        public String getUserIdHeader() {
            return userIdHeader;
        }

        public void setUserIdHeader(String userIdHeader) {
            this.userIdHeader = userIdHeader;
        }
    }
    
    /**
     * Cache configuration properties.
     */
    public static class CacheConfig {
        
        /** Schema cache TTL in minutes */
        private int schemaTtlMinutes = 30;
        
        /** Role privileges cache TTL in minutes */
        private int rolePrivilegesTtlMinutes = 30;
        
        /** GraphQL cache TTL in minutes */
        private int graphqlTtlMinutes = 30;

        public int getSchemaTtlMinutes() {
            return schemaTtlMinutes;
        }

        public void setSchemaTtlMinutes(int schemaTtlMinutes) {
            this.schemaTtlMinutes = schemaTtlMinutes;
        }

        public int getRolePrivilegesTtlMinutes() {
            return rolePrivilegesTtlMinutes;
        }

        public void setRolePrivilegesTtlMinutes(int rolePrivilegesTtlMinutes) {
            this.rolePrivilegesTtlMinutes = rolePrivilegesTtlMinutes;
        }

        public int getGraphqlTtlMinutes() {
            return graphqlTtlMinutes;
        }

        public void setGraphqlTtlMinutes(int graphqlTtlMinutes) {
            this.graphqlTtlMinutes = graphqlTtlMinutes;
        }
    }
}
