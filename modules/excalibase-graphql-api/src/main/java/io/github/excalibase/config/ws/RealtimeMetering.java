package io.github.excalibase.config.ws;

import io.github.excalibase.observability.requests.RequestMetrics;

/**
 * Counts a realtime session and the changes delivered on it under its path project.
 * A no-op when request metrics are off.
 */
final class RealtimeMetering {

    private static final String COUNTED_KEY = "excalibase.requestMetrics.counted";

    private final RequestMetrics metrics;

    RealtimeMetering(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    void opened(WsSession session) {
        String projectId = projectOf(session);
        if (metrics != null && projectId != null && metrics.connectionOpened(projectId)) {
            session.getAttributes().put(COUNTED_KEY, Boolean.TRUE);
        }
    }

    // Only a session counted on open is uncounted, and only once.
    void closed(WsSession session) {
        String projectId = projectOf(session);
        if (metrics != null && projectId != null && session.getAttributes().remove(COUNTED_KEY) != null) {
            metrics.connectionClosed(projectId);
        }
    }

    void delivered(WsSession session) {
        String projectId = projectOf(session);
        if (metrics != null && projectId != null) {
            metrics.messageDelivered(projectId);
        }
    }

    private static String projectOf(WsSession session) {
        return (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
    }
}
