package io.github.excalibase.config.ws;

import io.github.excalibase.schema.ExposureSource;
import io.github.excalibase.schema.TableExposure;
import io.github.excalibase.security.CallerRole;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.RlsOp;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * The one exposure check realtime needs.
 *
 * <p>Everywhere else, a resource the caller was not granted is simply missing
 * from the schema they were served, so there is nothing to check. Realtime is
 * the exception: a CDC event arrives from the database, not from a field the
 * caller asked for, so nothing consults the schema on its way out. This asks the
 * question explicitly, once, for both websocket handlers.
 */
@Component
public class RealtimeExposureGate {

    private final ExposureSource exposureSource;

    public RealtimeExposureGate(ExposureSource exposureSource) {
        this.exposureSource = exposureSource;
    }

    /**
     * True when this session's subscriber may read {@code resource}
     * ({@code schema.table}). Sessions with no project context pass: they are the
     * single-tenant path, where there is no grant configuration to apply.
     */
    public boolean permitsRead(WebSocketSession session, String resource) {
        String projectId = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        if (exposureSource == null || projectId == null) {
            return true;
        }
        JwtClaims claims = (JwtClaims) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY);
        String orgSlug = claims != null ? claims.orgSlug() : null;
        // Exposure's role is derived from whether this session authenticated, not
        // from the free-form `role` claim — that one drives row-level policies and
        // matches neither of the two roles a grant may name.
        TableExposure exposure = exposureSource.exposureFor(orgSlug, projectId, CallerRole.of(claims));
        return exposure == null || exposure.permits(resource, RlsOp.SELECT);
    }
}
