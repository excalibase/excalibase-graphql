package io.github.excalibase.rest.compiler;

/** {@link RestMysqlContract} on MySQL 8.0. */
class RestMysql80IntegrationTest extends RestMysqlContract {

    @Override
    String image() {
        return "mysql:8.0";
    }
}
