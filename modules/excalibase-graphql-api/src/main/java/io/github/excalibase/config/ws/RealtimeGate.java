package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.security.Principal;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where both websocket handlers get what a subscription may deliver: the role's access plan decides
 * whether the table can be subscribed to at all, then judges and projects each change. A session with
 * no project in its path is the single-tenant path and passes changes through.
 */
final class RealtimeGate {

    private RealtimeGate() {
    }

    /**
     * The result of opening a subscription: a delivery rule, or why there is none.
     *
     * @param unavailable the project's permissions cannot be read, so the session must close
     */
    record Opening(Delivery delivery, String refusal, boolean unavailable) {

        static Opening refused(String refusal) {
            return new Opening(null, refusal, false);
        }
    }

    /** One subscription's delivery rule; {@code access} is null for the pass-through path. */
    record Delivery(AccessPlans.RealtimeAccess access) {

        /** The payload the subscriber may see, or empty when this change is not theirs. */
        Optional<Object> render(CDCEvent event, Object parsed) {
            if (access == null) {
                return Optional.of(parsed);
            }
            if (!(parsed instanceof Map<?, ?> data)) {
                return Optional.empty();
            }
            return access.filter().render(event.type(), asRow(data), access.probes())
                    .map(visible -> RestTimeFormat.apply(visible, access.table(), access.view()));
        }
    }

    /** Reads a change's JSON with fractional numbers exact, so a numeric column is judged as the database holds it. */
    static ObjectReader changeReader(ObjectMapper objectMapper) {
        return objectMapper.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    static Opening open(AccessPlans plans, WebSocketSession session, String subscriptionKey) {
        String projectId = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        if (plans == null || projectId == null) {
            return new Opening(new Delivery(null), null, false);
        }
        Principal principal = GraphQLWebSocketHandler.principalOf(session);
        String orgSlug = principal != null && principal.claims() != null ? principal.claims().orgSlug() : null;
        try {
            return plans.realtime(orgSlug, projectId, principal, subscriptionKey)
                    .map(access -> new Opening(new Delivery(access), null, false))
                    .orElseGet(() -> Opening.refused("Unknown subscription: " + subscriptionKey));
        } catch (PermissionsUnavailableException e) {
            return new Opening(null, PermissionsUnavailableException.CODE, true);
        } catch (PermissionEvaluationException e) {
            return Opening.refused(e.code());
        } catch (IllegalStateException e) {
            return Opening.refused("Unauthenticated: send connection_init first");
        }
    }

    private static Map<String, Object> asRow(Map<?, ?> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        data.forEach((key, value) -> row.put(String.valueOf(key), value));
        return row;
    }
}
