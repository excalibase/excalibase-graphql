package io.github.excalibase.nats;

import io.nats.client.Options;

import java.time.Duration;

/**
 * Builds the NATS dial options every excalibase client shares.
 *
 * <p>The platform's NATS server scopes subject permissions per principal, so a
 * connection carries a username/password and the reply-inbox prefix that
 * principal is allowed to subscribe to. Without the matching prefix the
 * connection authenticates but every request/reply times out, because replies
 * land on a subject the principal may not read.
 *
 * <p>A blank username yields the previous, unauthenticated behaviour, which
 * keeps local development against a plain NATS server working.
 */
public final class NatsOptionsFactory {

    private static final Duration RECONNECT_WAIT = Duration.ofSeconds(2);

    private NatsOptionsFactory() {
    }

    /**
     * @param url         NATS server URL
     * @param username    principal name, or blank for an anonymous connection
     * @param password    principal secret, ignored when {@code username} is blank
     * @param inboxPrefix reply-inbox prefix, or blank for the client default
     * @return a builder the caller may extend with listeners before building
     */
    public static Options.Builder builder(String url, String username, String password, String inboxPrefix) {
        Options.Builder builder = Options.builder()
                .server(url)
                .reconnectWait(RECONNECT_WAIT)
                .maxReconnects(-1);
        if (hasText(username)) {
            builder.userInfo(username, password == null ? "" : password);
        }
        if (hasText(inboxPrefix)) {
            builder.inboxPrefix(inboxPrefix);
        }
        return builder;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
