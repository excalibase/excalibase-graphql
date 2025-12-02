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
package io.github.excalibase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.ssl.SslHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.jmx.JmxEndpointAutoConfiguration;

@SpringBootApplication(exclude = {
        JmxAutoConfiguration.class,
        SpringApplicationAdminJmxAutoConfiguration.class,
        JmxEndpointAutoConfiguration.class,
        SslAutoConfiguration.class,
        SslHealthContributorAutoConfiguration.class,
        ProjectInfoAutoConfiguration.class
})
public class ExcalibaseGraphqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExcalibaseGraphqlApplication.class, args);
    }
}
