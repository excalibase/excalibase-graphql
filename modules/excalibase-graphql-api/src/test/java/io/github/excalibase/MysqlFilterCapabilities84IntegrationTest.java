package io.github.excalibase;

import org.testcontainers.containers.MySQLContainer;

/** {@link MysqlFilterCapabilitiesContract} on MySQL 8.4. */
class MysqlFilterCapabilities84IntegrationTest extends MysqlFilterCapabilitiesContract {

    static final MySQLContainer<?> MYSQL = container("mysql:8.4");

    @Override
    MySQLContainer<?> database() {
        return MYSQL;
    }
}
