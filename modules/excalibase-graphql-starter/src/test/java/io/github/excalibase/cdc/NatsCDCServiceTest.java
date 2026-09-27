package io.github.excalibase.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
        verify(subscriptionService, never()).publish(any(String.class), any(CDCEvent.class));
    }

    @Test
    @DisplayName("stop is safe to call when start never ran")
    void stop_withNoConnection_doesNotThrow() {
        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop closes the connection and marks the service stopped")
    void stop_closesConnection() throws Exception {
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        ReflectionTestUtils.setField(service, "natsConnection", conn);
        ReflectionTestUtils.setField(service, "running", new java.util.concurrent.atomic.AtomicBoolean(true));

        service.stop();

        verify(conn).close();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("handleMessage publishes INSERT events via SubscriptionService (legacy null tenant)")
    void handleMessage_insertEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish((String) org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(event));
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage publishes UPDATE events")
    void handleMessage_updateEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("UPDATE", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish((String) org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(event));
    }

    @Test
    @DisplayName("handleMessage publishes DELETE events")
    void handleMessage_deleteEvent_publishes() throws Exception {
        CDCEvent event = new CDCEvent("DELETE", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish((String) org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(event));
    }

    @Test
    @DisplayName("handleMessage ignores non-DML, non-DDL events (e.g. HEARTBEAT)")
    void handleMessage_heartbeat_doesNotPublish() throws Exception {
        CDCEvent event = new CDCEvent("HEARTBEAT", null, null, null, 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService, never()).publish(any(String.class), any(CDCEvent.class));
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage with DML event but null table does not publish")
    void handleMessage_dmlWithNullTable_doesNotPublish() throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", null, "{}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService, never()).publish(any(String.class), any(CDCEvent.class));
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
        verify(subscriptionService, never()).publish(any(String.class), any(CDCEvent.class));
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
    @DisplayName("handleMessage terminates a message that can never be parsed instead of acking it")
    void handleMessage_invalidJson_terminates() throws Exception {
        Message msg = org.mockito.Mockito.mock(Message.class);
        when(msg.getData()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
        when(msg.getSubject()).thenReturn("cdc.customers");

        invokeHandleMessage(msg);

        verify(msg).term();
        verify(msg, never()).ack();
        verify(subscriptionService, never()).publish(any(String.class), any(CDCEvent.class));
    }

    @Test
    @DisplayName("handleMessage asks for redelivery when a schema reload fails, and does not ack")
    void handleMessage_reloadFails_naksForRedelivery() throws Exception {
        service.setSchemaReloadCallback(() -> {
            throw new IllegalStateException("database unavailable");
        });
        Message msg = mockMessage(new CDCEvent("DDL", "public", null, null, 1000L));

        invokeHandleMessage(msg);

        verify(msg).nakWithDelay(any(Duration.class));
        verify(msg, never()).ack();
    }

    @Test
    @DisplayName("handleMessage acks only after the event was handed to subscribers")
    void handleMessage_acksAfterPublishing() throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1000L);
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        InOrder order = inOrder(subscriptionService, msg);
        order.verify(subscriptionService).publish((String) org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(event));
        order.verify(msg).ack();
    }

    @Test
    @DisplayName("stop releases a subscription owned by a dispatcher without throwing")
    void stop_unsubscribesThroughTheDispatcher() throws Exception {
        JetStreamSubscription sub = org.mockito.Mockito.mock(JetStreamSubscription.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        ReflectionTestUtils.setField(service, "subscription", sub);
        ReflectionTestUtils.setField(service, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(service, "natsConnection", conn);

        service.stop();

        verify(dispatcher).unsubscribe(sub);
        verify(sub, never()).unsubscribe();
        verify(conn).close();
    }

    // ─── Tenant parsing from subject ─────────────────────────────────────────

    @Test
    @DisplayName("handleMessage with tenant-in-subject=false publishes with null tenant (legacy)")
    void handleMessage_tenantModeOff_publishesWithNullTenant() throws Exception {
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        ReflectionTestUtils.setField(service, "tenantInSubject", false);

        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1L);
        // tenant mode off — subject is not parsed, stub not needed
        Message msg = mockMessage(event);

        invokeHandleMessage(msg);

        verify(subscriptionService).publish((String) org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(event));
        verify(msg).ack();
    }

    @Test
    @DisplayName("handleMessage with tenant-in-subject=true extracts single-token tenant from subject")
    void handleMessage_tenantMode_singleTokenTenant() throws Exception {
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        ReflectionTestUtils.setField(service, "tenantInSubject", true);

        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1L);
        Message msg = mockMessageWithSubject(event, "cdc.proj_abc123.public.customers");

        invokeHandleMessage(msg);

        verify(subscriptionService).publish("proj_abc123", event);
    }

    @Test
    @DisplayName("handleMessage with tenant-in-subject=true extracts multi-token tenant (e.g. org.project)")
    void handleMessage_tenantMode_multiTokenTenant() throws Exception {
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        ReflectionTestUtils.setField(service, "tenantInSubject", true);

        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1L);
        Message msg = mockMessageWithSubject(event, "cdc.acme-corp.app-a.public.customers");

        invokeHandleMessage(msg);

        verify(subscriptionService).publish("acme-corp.app-a", event);
    }

    @Test
    @DisplayName("handleMessage with tenant-in-subject=true and no tenant tokens falls back to null tenant")
    void handleMessage_tenantMode_noTenantTokens_fallsBackToNull() throws Exception {
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        ReflectionTestUtils.setField(service, "tenantInSubject", true);

        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":1}", 1L);
        Message msg = mockMessageWithSubject(event, "cdc.public.customers");

        invokeHandleMessage(msg);

        // Subject has only prefix + schema + table (no tenant segment). Publish with null tenant.
        verify(subscriptionService).publish(
                (String) org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(event));
    }

    private Message mockMessageWithSubject(CDCEvent event, String subject) throws Exception {
        Message msg = org.mockito.Mockito.mock(Message.class);
        when(msg.getData()).thenReturn(objectMapper.writeValueAsBytes(event));
        when(msg.getSubject()).thenReturn(subject);
        return msg;
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
