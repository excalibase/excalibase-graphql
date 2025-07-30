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
package io.github.excalibase.constant;

/**
 * Constants for GraphQL mutation operation names and patterns.
 */
public class MutationConstant {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MutationConstant() {
    }

    // Mutation operation prefixes
    
    /** Prefix for create mutation operations */
    public static final String CREATE_PREFIX = "create";
    
    /** Prefix for update mutation operations */
    public static final String UPDATE_PREFIX = "update";
    
    /** Prefix for delete mutation operations */
    public static final String DELETE_PREFIX = "delete";
    
    /** Prefix for bulk create mutation operations */
    public static final String CREATE_MANY_PREFIX = "createMany";
    
    // Mutation operation suffixes
    
    /** Suffix for bulk operations */
    public static final String BULK_SUFFIX = "s";
    
    /** Suffix for operations with relationships */
    public static final String WITH_RELATIONS_SUFFIX = "WithRelations";
    
    // Relationship operation names
    
    /** Connect operation for relationships */
    public static final String CONNECT = "connect";
    
    /** Create operation for relationships */
    public static final String CREATE = "create";
    
    /** CreateMany operation for relationships */
    public static final String CREATE_MANY = "createMany";
    
    // Mutation field descriptions
    
    /** Description template for create mutations */
    public static final String CREATE_DESCRIPTION_TEMPLATE = "Create a new %s record";
    
    /** Description template for update mutations */
    public static final String UPDATE_DESCRIPTION_TEMPLATE = "Update an existing %s record";
    
    /** Description template for delete mutations */
    public static final String DELETE_DESCRIPTION_TEMPLATE = "Delete a %s record";
    
    /** Description template for bulk create mutations */
    public static final String CREATE_MANY_DESCRIPTION_TEMPLATE = "Create multiple %s records in a single operation";
    
    /** Description template for create with relationships mutations */
    public static final String CREATE_WITH_RELATIONS_DESCRIPTION_TEMPLATE = "Create a %s record with related records";
    
    // Input type descriptions
    
    /** Description template for create input types */
    public static final String CREATE_INPUT_DESCRIPTION_TEMPLATE = "Input for creating new %s records";
    
    /** Description template for update input types */
    public static final String UPDATE_INPUT_DESCRIPTION_TEMPLATE = "Input for updating %s records";
    
    /** Description template for relationship connection */
    public static final String CONNECT_DESCRIPTION_TEMPLATE = "Connect to existing %s";
    
    /** Description template for relationship creation */
    public static final String CREATE_RELATIONSHIP_DESCRIPTION_TEMPLATE = "Create new %s and connect";
    
    /** Description template for bulk relationship creation */
    public static final String CREATE_MANY_RELATIONSHIP_DESCRIPTION_TEMPLATE = "Create multiple %s records related to this %s";
} 