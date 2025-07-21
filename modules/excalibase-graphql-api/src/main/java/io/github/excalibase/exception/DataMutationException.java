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
package io.github.excalibase.exception;

/**
 * Exception thrown when database mutation operations fail.
 * 
 * <p>This runtime exception is thrown by mutation resolvers when database
 * modification operations (create, update, delete) encounter errors. It wraps
 * underlying database exceptions and provides meaningful error messages for
 * GraphQL mutation failures.</p>
 * 
 * <p>Common scenarios that trigger this exception:</p>
 * <ul>
 *   <li>Database constraint violations (foreign key, unique, not null)</li>
 *   <li>SQL syntax errors in generated queries</li>
 *   <li>Database connection failures during mutations</li>
 *   <li>Transaction rollback situations</li>
 *   <li>Invalid input data that cannot be processed</li>
 * </ul>
 *
 * @see io.github.excalibase.schema.mutator.IDatabaseMutator
 */
public class DataMutationException extends RuntimeException {
    
    /**
     * Constructs a new DataMutationException with the specified detail message.
     * 
     * @param message the detail message explaining the mutation failure
     */
    public DataMutationException(String message) {
        super(message);
    }

    /**
     * Constructs a new DataMutationException with the specified detail message and cause.
     * 
     * @param message the detail message explaining the mutation failure
     * @param cause the underlying cause of the mutation failure
     */
    public DataMutationException(String message, Throwable cause) {
        super(message, cause);
    }
}
