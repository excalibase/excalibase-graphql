package io.github.excalibase.config.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.excalibase.support.CapturedLogs;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.Parser;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import io.github.excalibase.security.JwtService;
import io.micronaut.websocket.WebSocketSession;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A refused or silent WebSocket session leaves a trace in the log: the init frame arriving, the refusal and its
 * reason category, and a frame that had to wait for (or gave up waiting for) the open handler. Never the token.
 */
class WsRefusalLogTest {

    private static final String SECRET_TOKEN = "eyJhbGciOiJFUzI1NiJ9.super-secret-payload.signature";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturedLogs appender = new CapturedLogs();
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GraphQLWebSocketHandler.class);
    private final Logger sessionLog = (Logger) LoggerFactory.getLogger(MicronautWsSession.class);
    private Level handlerLevel;
    private Level sessionLevel;

    @BeforeEach
    void attach() {
        handlerLevel = handlerLog.getLevel();
        sessionLevel = sessionLog.getLevel();
        handlerLog.setLevel(Level.DEBUG);
        sessionLog.setLevel(Level.DEBUG);
        appender.start();
        handlerLog.addAppender(appender);
        sessionLog.addAppender(appender);
    }

    @AfterEach
    void detach() {
        handlerLog.detachAppender(appender);
        sessionLog.detachAppender(appender);
        handlerLog.setLevel(handlerLevel);
        sessionLog.setLevel(sessionLevel);
    }

    private List<ILoggingEvent> logged(Level level) {
        return appender.events().stream().filter(event -> event.getLevel() == level).toList();
    }

    private static WsSession session(String id) throws Exception {
        WsSession session = mock(WsSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(
                Map.of(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "p1")));
        doAnswer(invocation -> null).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private GraphQLWebSocketHandler handler(boolean jwtEnabled, boolean tokenRequired) throws Exception {
        return handler(jwtEnabled, tokenRequired, null, WsRuntime.inline());
    }

    private GraphQLWebSocketHandler handler(boolean jwtEnabled, boolean tokenRequired, JwtService jwtService,
                                            WsRuntime runtime) throws Exception {
        GraphQLWebSocketHandler handler = new GraphQLWebSocketHandler(new SubscriptionService(), mapper,
                jwtService, new WebSocketHeartbeat(0), null, null,
                new ParsedQueries(1_000_000, Parser::parse), runtime);
        set(handler, "jwtEnabled", jwtEnabled);
        set(handler, "wsAuthRequired", tokenRequired);
        return handler;
    }

    private static void set(Object target, String field, boolean value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.setBoolean(target, value);
    }

    @Test
    @DisplayName("connection_init is logged when it arrives, before any auth decision")
    void connectionInitIsLogged() throws Exception {
        handler(false, false).onMessage(session("sess-init"), "{\"type\":\"connection_init\",\"payload\":{}}");

        assertThat(logged(Level.INFO)).anyMatch(event ->
                event.getFormattedMessage().contains("connection_init") && event.getFormattedMessage().contains("sess-init"));
    }

    @Test
    @DisplayName("a refusal is logged with its reason category and the session id")
    void refusalIsLogged() throws Exception {
        handler(true, true).onMessage(session("sess-refused"), "{\"type\":\"connection_init\",\"payload\":{}}");

        assertThat(logged(Level.INFO)).anyMatch(event ->
                event.getFormattedMessage().contains("refused")
                        && event.getFormattedMessage().contains("sess-refused")
                        && event.getFormattedMessage().contains(WsSessionAuth.MISSING_TOKEN));
    }

    @Test
    @DisplayName("a refusal never logs the token the client sent")
    void refusalNeverLogsTheToken() throws Exception {
        JwtService verifier = new JwtService("0123456789abcdef0123456789abcdef");
        handler(true, true, verifier, WsRuntime.inline()).onMessage(session("sess-token"), mapper.writeValueAsString(Map.of("type", "connection_init",
                "payload", Map.of("Authorization", "Bearer " + SECRET_TOKEN))));

        assertThat(logged(Level.INFO)).anyMatch(event -> event.getFormattedMessage().contains("refused"));
        assertThat(appender.events()).noneMatch(event -> event.getFormattedMessage().contains("super-secret-payload"));
    }

    @Test
    @DisplayName("a connection refused for the socket limit is logged with the session id and reason")
    void limitRefusalIsLogged() throws Exception {
        WsRuntime full = mock(WsRuntime.class);
        when(full.admit(any())).thenReturn(false);

        handler(false, false, null, full).afterConnectionEstablished(session("sess-limit"));

        assertThat(logged(Level.INFO)).anyMatch(event ->
                event.getFormattedMessage().contains("sess-limit")
                        && event.getFormattedMessage().contains("refused")
                        && event.getFormattedMessage().contains(WsClose.LIMIT.reason()));
    }

    private static WebSocketSession micronautSession(String id) {
        Map<String, Object> store = new ConcurrentHashMap<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.put(anyString(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        });
        when(session.get(anyString(), any(Class.class))).thenAnswer(invocation ->
                Optional.ofNullable(store.get((String) invocation.getArgument(0))));
        return session;
    }

    @Test
    @DisplayName("a frame that arrives before the open handler has run is logged as waiting")
    void earlyFrameIsLogged() {
        WebSocketSession session = micronautSession("sess-early");
        try (var other = Executors.newSingleThreadExecutor()) {
            other.submit(() -> {
                await().until(() -> !logged(Level.WARN).isEmpty());
                MicronautWsSession.markOpened(session);
                return null;
            });
            MicronautWsSession.awaitOpened(session, Duration.ofSeconds(5));
        }

        assertThat(logged(Level.WARN)).anyMatch(event ->
                event.getFormattedMessage().contains("sess-early") && event.getFormattedMessage().contains("before"));
    }

    @Test
    @DisplayName("a frame on a session already opened logs nothing")
    void openedSessionIsQuiet() {
        WebSocketSession session = micronautSession("sess-quiet");
        MicronautWsSession.markOpened(session);

        MicronautWsSession.awaitOpened(session, Duration.ofSeconds(1));

        assertThat(logged(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("a frame that gives up waiting for the open handler is logged as dropped")
    void droppedFrameIsLogged() {
        WebSocketSession session = micronautSession("sess-gave-up");

        Duration limit = Duration.ofMillis(100);

        assertThatThrownBy(() -> MicronautWsSession.awaitOpened(session, limit))
                .isInstanceOf(IllegalStateException.class);

        assertThat(logged(Level.WARN)).anyMatch(event ->
                event.getFormattedMessage().contains("sess-gave-up")
                        && event.getFormattedMessage().contains("did not finish within"));
    }
}
