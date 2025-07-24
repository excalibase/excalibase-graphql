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
package io.github.excalibase.annotation;

import org.springframework.stereotype.Service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation for marking Excalibase service implementations.
 * 
 * <p>This annotation extends Spring's {@code @Service} annotation and provides
 * additional metadata for service identification within the Excalibase projects.
 * It allows services to be identified by a specific service name, which is used
 * for dynamic service lookup and database-specific implementation selection.</p>
 * 
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * @ExcalibaseService(serviceName = "Postgres")
 * public class PostgresSchemaGenerator implements IGraphQLSchemaGenerator {
 *     // Implementation for PostgreSQL
 * }
 * }
 * </pre>
 *
 * @see org.springframework.stereotype.Service
 * @see io.github.excalibase.service.ServiceLookup
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Service
public @interface ExcalibaseService {
    String serviceName();
}
