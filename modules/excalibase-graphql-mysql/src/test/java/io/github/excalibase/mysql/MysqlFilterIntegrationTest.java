package io.github.excalibase.mysql;

/** {@link MysqlFilterContract} on MySQL 8.4. */
class MysqlFilterIntegrationTest extends MysqlFilterContract {

    @Override
    String image() {
        return "mysql:8.4";
    }
}
