package io.github.excalibase.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NatsCDCServiceTest {

    @Mock
    SubscriptionService subscriptionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NatsCDCService service;

    @BeforeEach
    void setUp() {
        service = new NatsCDCService(subscriptionService, objectMapper);
    }

    @Test
    @DisplayName("start does nothing and leaves running=false when nats is disabled")
    void start_disabled_isNoop() {
        ReflectionTestUtils.setField(service, "natsEnabled", false);

        service.start();

        assertThat(service.isRunning()).isFalse();
        verify(subscriptionService, never()).publish(any());
    }

    @Test
    @DisplayName("stop is safe to call when start never ran")
    void stop_withNoConnection_doesNotThrow() {
        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop closes connection and unsubscribes when they exist")
    void stop_closesConnectionAndSubscription() throws Exception {
        JetStreamSubscription sub = org.mockito.Mockito.mock(JetStreamSubscription.class);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        ReflectionTestUtils.setField(service, "subscription", sub);
        ReflectionTestUtils.setField(service, "natsConnection", conn);
        ReflectionTestUtils.setField(service, "running", new java.util.concurrent.atomic.AtomicBoolean(true));

        service.stop();

        verify(sub).unsubscribe();
        verify(conn).close();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("handleMessage publishes INSERT events via SubscriptionService")
    void handleMessage_insertEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish(event);
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage publishes UPDATE events")
    void handleMessage_updateEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("UPDATE", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish(event);
    }

    @Test
    @DisplayName("handleMessage publishes DELETE events")
    void handleMessage_deleteEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("DELETE", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish(event);
    }

    @Test
    @DisplayName("handleMessage ignores non-DML, non-DDL events (e.g. HEARTBEAT)")
    void handleMessage_heartbeat_doesNotPublish() throws Exception {
        CDCEvent event = new CDCEvent("HEARTBEAT", null, null, null, 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService, never()).publish(any());
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage with DML event but null table does not publish")
    void handleMessage_dmlWithNullTable_doesNotPublish() throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", null, "{}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService, never()).publish(any());
    }

    @Test
    @DisplayName("handleMessage triggers every registered schema reload callback on DDL")
    void handleMessage_ddlEvent_invokesAllCallbacks() throws Exception {
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        service.setSchemaReloadCallback(firstCount::incrementAndGet);
        service.setSchemaReloadCallback(secondCount::incrementAndGet);

        CDCEvent ddl = new CDCEvent("DDL", "public", null, null, 1000L);
        Message msg = mockMessage(ddl);

        invokeHandleMessage(msg);

        assertThat(firstCount).hasValue(1);
        assertThat(secondCount).hasValue(1);
        verify(subscriptionService, never()).publish(any());
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage with DDL but no callbacks registered does not throw")
    void handleMessage_ddlEvent_noCallbacks_doesNotThrow() throws Exception {
        CDCEvent ddl = new CDCEvent("DDL", "public", null, null, 1000L);
        Message msg = mockMessage(ddl);

        invokeHandleMessage(msg);

        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage with invalid JSON still acks to avoid redelivery loop")
    void handleMessage_invalidJson_acksAnyway() throws Exception {
        Message msg = org.mockito.Mockito.mock(Message.class);
        when(msg.getData()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
        when(msg.getSubject()).thenReturn("cdc.customers");

        invokeHandleMessage(msg);

        verify(msg, times(1)).ack();
        verify(subscriptionService, never()).publish(any());
    }

    private Message mockMessage(CDCEvent event) throws Exception {
        Message msg = org.mockito.Mockito.mock(Message.class);
        byte[] bytes = objectMapper.writeValueAsBytes(event);
        when(msg.getData()).thenReturn(bytes);
        return msg;
    }

    private void invokeHandleMessage(Message msg) throws Exception {
        Method method = NatsCDCService.class.getDeclaredMethod("handleMessage", Message.class);
        method.setAccessible(true);
        method.invoke(service, msg);
    }
}
