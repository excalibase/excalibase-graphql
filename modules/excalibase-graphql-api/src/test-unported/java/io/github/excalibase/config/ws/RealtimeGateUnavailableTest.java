package io.github.excalibase.config.ws;

import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SchemaUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A subscription opened while the project's database cannot be read is told so and closed, not left open. */
class RealtimeGateUnavailableTest {

    @Test
    void unreadableDatabase_refusesTheSubscriptionAsUnavailable() {
        AccessPlans plans = mock(AccessPlans.class);
        when(plans.realtime(any(), any(), any(), any()))
                .thenThrow(new SchemaUnavailableException("being restored", new SQLException("link failure")));
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "proj_restore01");
        when(session.getAttributes()).thenReturn(attributes);

        RealtimeGate.Opening opening = RealtimeGate.open(plans, session, "orders");

        assertThat(opening.delivery()).isNull();
        assertThat(opening.refusal()).isEqualTo("project_unavailable");
        assertThat(opening.unavailable()).isTrue();
    }
}
