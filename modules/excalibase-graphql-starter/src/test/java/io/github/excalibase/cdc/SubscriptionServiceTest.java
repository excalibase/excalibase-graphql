package io.github.excalibase.cdc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class SubscriptionServiceTest {

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService();
    }

    @Test
    @DisplayName("publish(null, event) with null tenant routes to null-tenant subscriber (backward compat)")
    void publishWithoutTenant_routesToNullTenantSubscriber() {
        List<CDCEvent> received = new ArrayList<>();
        Disposable disposable = service.subscribe(null, "public_customer")
                .subscribe(received::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "public_customer"));
            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1000L);
            service.publish(null, event);

            awaitTrue(() -> received.size() == 1);
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().table()).isEqualTo("customer");
        } finally {
            disposable.dispose();
        }
    }

    @Test
    @DisplayName("publish(tenantA, event) delivers ONLY to tenantA subscriber, not tenantB")
    void publish_tenantScoped_isolatesTenants() {
        List<CDCEvent> aReceived = new ArrayList<>();
        List<CDCEvent> bReceived = new ArrayList<>();
        Disposable a = service.subscribe("tenant-a", "public_customer").subscribe(aReceived::add);
        Disposable b = service.subscribe("tenant-b", "public_customer").subscribe(bReceived::add);
        try {
            awaitTrue(() -> service.hasSubscribers("tenant-a", "public_customer")
                    && service.hasSubscribers("tenant-b", "public_customer"));

            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1L);
            service.publish("tenant-a", event);

            awaitTrue(() -> aReceived.size() == 1);
            assertThat(aReceived).hasSize(1);
            assertThat(bReceived).isEmpty();
        } finally {
            a.dispose();
            b.dispose();
        }
    }

    @Test
    @DisplayName("publish(tenantA, event) NOT delivered to null-tenant subscriber on same table")
    void publish_withTenant_notDeliveredToNullTenantSubscriber() {
        List<CDCEvent> nullTenantReceived = new ArrayList<>();
        Disposable d = service.subscribe(null, "public_customer").subscribe(nullTenantReceived::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "public_customer"));
            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1L);
            service.publish("tenant-a", event);

            sleep(200);
            assertThat(nullTenantReceived).isEmpty();
        } finally {
            d.dispose();
        }
    }

    @Test
    @DisplayName("hasSubscribers(tenantId, table) tracks subscribers per tenant")
    void hasSubscribers_isTenantScoped() {
        assertThat(service.hasSubscribers("tenant-a", "public_customer")).isFalse();
        Disposable d = service.subscribe("tenant-a", "public_customer").subscribe(e -> {});
        try {
            awaitTrue(() -> service.hasSubscribers("tenant-a", "public_customer"));
            assertThat(service.hasSubscribers("tenant-a", "public_customer")).isTrue();
            assertThat(service.hasSubscribers("tenant-b", "public_customer")).isFalse();
        } finally {
            d.dispose();
        }
    }

    private static void awaitTrue(BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            sleep(20);
        }
        fail("Condition not met within 2 seconds");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
