package io.github.excalibase.config.ws;

import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SurfaceNotServedException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A database with no realtime surface answers the subscription with a clear refusal, not a crash. */
class RealtimeGateNotServedTest {

    @Test
    void aSurfaceTheDatabaseDoesNotHaveIsRefusedByName() {
        AccessPlans plans = mock(AccessPlans.class);
        when(plans.realtime(any(), any(), any(), any())).thenThrow(new SurfaceNotServedException("not served"));
        WsSession session = mock(WsSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "p1");
        when(session.getAttributes()).thenReturn(attributes);

        RealtimeGate.Opening opening = RealtimeGate.open(plans, session, "orders", true);

        assertThat(opening.delivery()).isNull();
        assertThat(opening.refusal()).isEqualTo(SurfaceNotServedException.CODE);
        assertThat(opening.unavailable()).isFalse();
    }
}
