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
package io.github.excalibase.model;

import java.util.List;

/**
 * Represents a PostgreSQL function that can be used as a computed field.
 *
 * <p>For a function to be recognized as a computed field, it must follow the pattern:
 * <pre>
 * CREATE FUNCTION table_field_name(table_row table_name) RETURNS return_type
 * </pre>
 *
 * <p>Example:
 * <pre>
 * CREATE FUNCTION customer_full_name(customer_row customer) RETURNS TEXT AS $$
 *   SELECT customer_row.first_name || ' ' || customer_row.last_name
 * $$ LANGUAGE sql STABLE;
 * </pre>
 */
public class ComputedFieldFunction {

    /**
     * The function name (e.g., "customer_full_name")
     */
    private String functionName;

    /**
     * The table this function is associated with (e.g., "customer")
     */
    private String tableName;

    /**
     * The field name in GraphQL schema (e.g., "full_name")
     * Extracted from function name by removing table prefix
     */
    private String fieldName;

    /**
     * The return type of the function (PostgreSQL type)
     */
    private String returnType;

    /**
     * The schema where the function is defined
     */
    private String schema;

    /**
     * Additional parameters beyond the table row parameter (if any)
     */
    private List<FunctionParameter> additionalParameters;

    public ComputedFieldFunction() {
    }

    public ComputedFieldFunction(String functionName, String tableName, String fieldName,
                                 String returnType, String schema) {
        this.functionName = functionName;
        this.tableName = tableName;
        this.fieldName = fieldName;
        this.returnType = returnType;
        this.schema = schema;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public List<FunctionParameter> getAdditionalParameters() {
        return additionalParameters;
    }

    public void setAdditionalParameters(List<FunctionParameter> additionalParameters) {
        this.additionalParameters = additionalParameters;
    }

    /**
     * Represents a function parameter.
     */
    public static class FunctionParameter {
        private String name;
        private String type;
        private int position;

        public FunctionParameter() {
        }

        public FunctionParameter(String name, String type, int position) {
            this.name = name;
            this.type = type;
            this.position = position;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
    }
}
