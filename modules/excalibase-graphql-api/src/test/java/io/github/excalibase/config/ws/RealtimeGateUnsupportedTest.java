package io.github.excalibase.config.ws;

import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SubscriptionsUnsupportedException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A database without a change stream refuses the subscription at the WebSocket, naming itself. */
class RealtimeGateUnsupportedTest {

    @Test
    void aDatabaseWithoutSubscriptionsRefusesThem() {
        AccessPlans plans = mock(AccessPlans.class);
        when(plans.realtime(any(), any(), any(), any()))
                .thenThrow(new SubscriptionsUnsupportedException("MySQL does not support subscriptions"));
        WsSession session = mock(WsSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "proj_mysql01");
        when(session.getAttributes()).thenReturn(attributes);

        RealtimeGate.Opening opening = RealtimeGate.open(plans, session, "orders");

        assertThat(opening.delivery()).isNull();
        assertThat(opening.refusal()).isEqualTo("MySQL does not support subscriptions");
        assertThat(opening.unavailable()).isFalse();
    }
}
