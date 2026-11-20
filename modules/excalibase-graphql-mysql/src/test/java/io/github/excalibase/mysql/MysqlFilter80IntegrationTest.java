package io.github.excalibase.mysql;

/** {@link MysqlFilterContract} on MySQL 8.0. */
class MysqlFilter80IntegrationTest extends MysqlFilterContract {

    @Override
    String image() {
        return "mysql:8.0";
    }
}
