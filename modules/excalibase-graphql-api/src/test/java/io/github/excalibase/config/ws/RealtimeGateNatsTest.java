package io.github.excalibase.config.ws;

import io.github.excalibase.schema.AccessPlans;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A subscription is never accepted when nothing can deliver it: realtime is fed by NATS. (EXC-803) */
class RealtimeGateNatsTest {

    private static WsSession sessionOf(String projectId) {
        WsSession session = mock(WsSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, projectId);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    void withoutNats_theSubscriptionIsRefusedByName() {
        AccessPlans plans = mock(AccessPlans.class);

        RealtimeGate.Opening opening = RealtimeGate.open(plans, sessionOf("proj-a"), "orders", false);

        assertThat(opening.delivery()).isNull();
        assertThat(opening.unavailable()).isFalse();
        assertThat(opening.refusal()).contains("realtime_unavailable").contains("NATS");
        verifyNoInteractions(plans);
    }

    @Test
    void withoutNats_thePassThroughPathIsRefusedToo() {
        RealtimeGate.Opening opening = RealtimeGate.open(null, sessionOf("proj-a"), "orders", false);

        assertThat(opening.delivery()).isNull();
        assertThat(opening.refusal()).contains("realtime_unavailable");
    }

    @Test
    void withNats_theSubscriptionOpensAsBefore() {
        RealtimeGate.Opening opening = RealtimeGate.open(null, sessionOf("proj-a"), "orders", true);

        assertThat(opening.delivery()).isNotNull();
    }
}
