package io.github.excalibase;

import org.testcontainers.containers.MySQLContainer;

/** {@link MysqlFilterCapabilitiesContract} on MySQL 8.0. */
class MysqlFilterCapabilities80IntegrationTest extends MysqlFilterCapabilitiesContract {

    static final MySQLContainer<?> MYSQL = container("mysql:8.0");

    @Override
    MySQLContainer<?> database() {
        return MYSQL;
    }
}
