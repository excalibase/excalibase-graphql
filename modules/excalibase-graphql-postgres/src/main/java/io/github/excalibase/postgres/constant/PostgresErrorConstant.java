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
package io.github.excalibase.postgres.constant;

/**
 * Error message constants for PostgreSQL operations.
 */
public class PostgresErrorConstant {
    private PostgresErrorConstant() {
    }

    // Common error prefixes
    public static final String ERROR_PREFIX = "Error";
    public static final String FAILED_PREFIX = "Failed";
    
    // Cursor and pagination errors
    public static final String CURSOR_REQUIRED_ERROR = "orderBy parameter is required for cursor-based pagination";
    public static final String INVALID_CURSOR_FORMAT = "Invalid cursor format";
    
    // Input validation errors
    public static final String INPUT_REQUIRED_TEMPLATE = "Input data is required for %s operation";
    public static final String NO_FIELDS_PROVIDED_TEMPLATE = "No %s provided for %s operation";
    public static final String PRIMARY_KEY_ERROR = "Primary key";
    
    // Operation error templates
    public static final String ERROR_OPERATION_TEMPLATE = "Error %s record: %s";
    public static final String ERROR_FETCHING_TEMPLATE = "Error fetching %s: %s";
    public static final String ERROR_PROCESSING_TEMPLATE = "Error processing %s: %s";
    
    // Database operation errors
    public static final String PARSE_ERROR_KEY = "parseError";
    public static final String PARSE_ERROR_TEMPLATE = "Failed to parse data as JSON: %s";
    public static final String INVALID_JSON_FORMAT = "Invalid JSON format for CDC event";
    public static final String INVALID_UUID_FORMAT = "Invalid UUID format";
    
    // PostgreSQL specific terms
    public static final String POSTGRESQL = "PostgreSQL";
    public static final String ROLE = "role";
    public static final String PRIVILEGES = "privileges";
    public static final String SUPERUSER = "superuser";
    public static final String DATABASE = "database";
    
    // CDC and subscription log messages
    public static final String TABLE_SUBSCRIPTION_RESOLVER_CALLED = "🔥 TABLE SUBSCRIPTION RESOLVER CALLED for table: {}";
    public static final String GRAPHQL_FIELD_LOG = "🔥 GraphQL Field: {}";
    public static final String ARGUMENTS_LOG = "🔥 Arguments: {}";
    public static final String CDC_STREAM_COMPLETED = "🔥 Table subscription: CDC table stream completed unexpectedly for {}";
    public static final String CDC_STREAM_ERROR = "🔥 Table subscription: CDC stream error for {}: ";
    public static final String CLIENT_SUBSCRIBED = "Client subscribed to real-time changes for table: {}";
    public static final String CLIENT_UNSUBSCRIBED = "Client unsubscribed from real-time changes for table: {}";
    public static final String COMBINED_STREAM_COMPLETED = "🔥 Table subscription: Combined stream completed for table: {}";
    public static final String SUBSCRIPTION_ERROR = "Subscription error for table {}: ";
    
    // CDC Service log messages
    public static final String CDC_SERVICE_STARTED = "CDC Service started successfully";
    public static final String CDC_SERVICE_FAILED = "Failed to start CDC Service";
    public static final String CDC_SERVICE_STOPPED = "CDC Service stopped";
    public static final String TABLE_STREAM_CLIENT_SUBSCRIBED = "📡 Table stream: Client subscribed to table events: {} (count: {})";
    public static final String TABLE_STREAM_CLIENT_UNSUBSCRIBED = "📡 Table stream: Client unsubscribed from table events: {} (count: {})";
    public static final String TABLE_STREAM_COMPLETED = "📡 Table stream: Stream completed unexpectedly for table: {}";
    public static final String TABLE_STREAM_ERROR = "📡 Table stream: Error occurred while streaming table events for {}";
    public static final String PROCESSING_CDC_EVENT = "Processing CDC event: type={}, table={}, data={}";
    public static final String INVALID_JSON_CDC = "Invalid JSON format for CDC event, table {}: {}";
    public static final String ERROR_VALIDATING_CDC = "Error validating CDC event data for table {}: {}";
    public static final String FAILED_EMIT_CDC = "Failed to emit CDC event for table {}: {}";
    public static final String RECREATING_SINK = "Recreating terminated sink for table: {}";
    public static final String FAILED_EMIT_AFTER_RECREATION = "Failed to emit CDC event after sink recreation for table {}: {}";
    public static final String SUCCESS_EMIT_AFTER_RECREATION = "Successfully emitted CDC event after sink recreation for table: {}";
    public static final String SUCCESS_EMIT_CDC = "Successfully emitted CDC event for table: {}";
    public static final String UNEXPECTED_ERROR_CDC = "Unexpected error handling CDC event: ";
    public static final String CLEANUP_CHECK = "📡 Table stream: Cleanup check for table {}, current subscribers: {}";
    public static final String NO_MORE_SUBSCRIBERS = "📡 Table stream: No more subscribers for table {}, removing sink";
    public static final String STILL_HAS_SUBSCRIBERS = "📡 Table stream: Table {} still has {} subscribers, keeping sink";
    public static final String NO_SINK_FOUND = "📡 Table stream: No sink or subscriber count found for table {} during cleanup";
    public static final String CREATED_NEW_SINK = "Created new CDC sink for table: {}";
    public static final String SINK_TERMINATED_RECREATING = "Table sink for {} is terminated, recreating it";
    public static final String SUCCESS_RECREATED_SINK = "Successfully recreated table sink for: {}";
    public static final String SINK_ACTIVE = "Table sink for {} is active, current subscriber count: {}";
}