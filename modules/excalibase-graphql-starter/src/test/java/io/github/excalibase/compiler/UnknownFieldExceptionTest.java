package io.github.excalibase.compiler;

import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownFieldExceptionTest {

    private static final String PUBLIC_TODOS = "publicTodos";

    @Test
    void theMessageStaysTheCompilersOwn() {
        assertThat(new UnknownFieldException(PUBLIC_TODOS)).hasMessage("Unknown field(s): publicTodos");
    }

    @Test
    void aRequestWithNoToken_isToldItRunsAsAnon() {
        String message = new UnknownFieldException(PUBLIC_TODOS).messageFor(null);

        assertThat(message).startsWith("Unknown field(s): publicTodos").contains("'anon'").contains("no token");
    }

    @Test
    void aRoleWithNoRule_isNamed() {
        String message = new UnknownFieldException(PUBLIC_TODOS)
                .messageFor(new Principal("editor", false, null, Map.of()));

        assertThat(message).contains("'editor'").contains("permission rule").doesNotContain("no token");
    }

    @Test
    void theServiceRole_isNotToldItLacksARule() {
        String message = new UnknownFieldException(PUBLIC_TODOS)
                .messageFor(new Principal(Principal.SERVICE, true, null, Map.of()));

        assertThat(message).isEqualTo("Unknown field(s): publicTodos");
    }
}
