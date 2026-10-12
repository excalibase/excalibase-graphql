package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCheckFailedExceptionTest {

    @Test
    void raiseWhen_castsTheMarkerOnlyWhenTheViolationHolds() {
        assertThat(PermissionCheckFailedException.raiseWhen("EXISTS (x)"))
                .isEqualTo("CAST(CASE WHEN EXISTS (x) THEN 'excalibase_permission_check_failed' END AS integer)");
    }

    @Test
    void find_recognisesTheDatabaseErrorAnywhereInTheCauseChain() {
        RuntimeException wrapped = new RuntimeException("statement failed", new SQLException(
                "ERROR: invalid input syntax for type integer: \"excalibase_permission_check_failed\""));

        assertThat(PermissionCheckFailedException.find(wrapped)).isPresent()
                .get().extracting(PermissionCheckFailedException::code).isEqualTo("permission_check_failed");
        assertThat(PermissionCheckFailedException.find(new RuntimeException("other"))).isEmpty();
    }

    @Test
    void find_ignoresAWrapperThatOnlyQuotesTheStatement() {
        RuntimeException grammar = new RuntimeException("bad SQL grammar [SELECT CAST(CASE WHEN x THEN "
                + "'excalibase_permission_check_failed' END AS integer)]", new SQLException(
                "ERROR: column \"match_id\" is of type bigint but expression is of type character varying"));

        assertThat(PermissionCheckFailedException.find(grammar)).isEmpty();
    }
}
