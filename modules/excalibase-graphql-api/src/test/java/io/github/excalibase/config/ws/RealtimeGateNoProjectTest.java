package io.github.excalibase.config.ws;

import io.github.excalibase.schema.AccessPlans;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A session that carries no project is never given the unfiltered change stream. */
class RealtimeGateNoProjectTest {

    @Test
    @DisplayName("a session with no project is refused, whatever plans are set")
    void noProjectIsRefused() {
        AccessPlans plans = mock(AccessPlans.class);
        WsSession session = mock(WsSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(attributes);

        RealtimeGate.Opening opening = RealtimeGate.open(plans, session, "orders", true);

        assertThat(opening.delivery()).isNull();
        assertThat(opening.refusal()).isNotBlank();
        verifyNoInteractions(plans);
    }
}
