package io.github.excalibase.rest.compiler;

/** {@link RestMysqlContract} on MySQL 8.4. */
class RestMysqlIntegrationTest extends RestMysqlContract {

    @Override
    String image() {
        return "mysql:8.4";
    }
}
