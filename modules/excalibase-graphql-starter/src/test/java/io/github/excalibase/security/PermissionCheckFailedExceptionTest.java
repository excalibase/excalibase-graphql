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
}
