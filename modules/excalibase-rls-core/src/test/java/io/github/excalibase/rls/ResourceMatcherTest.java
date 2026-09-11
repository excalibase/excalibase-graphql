package io.github.excalibase.rls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceMatcherTest {

    @Test
    void exactMatch() {
        assertThat(ResourceMatcher.matches("public.orders", "public.orders")).isTrue();
        assertThat(ResourceMatcher.matches("orders", "orders")).isTrue();
    }

    @Test
    void bareResourceMatchesSchemaQualifiedTable() {
        assertThat(ResourceMatcher.matches("orders", "public.orders")).isTrue();
        assertThat(ResourceMatcher.matches("rls_aio_notes", "public.rls_aio_notes")).isTrue();
    }

    @Test
    void noFalseMatch() {
        assertThat(ResourceMatcher.matches("orders", "public.payments")).isFalse();
        assertThat(ResourceMatcher.matches("orders", "ordersx")).isFalse();
        assertThat(ResourceMatcher.matches("orders", "public.x_orders")).isFalse();
        assertThat(ResourceMatcher.matches(null, "public.orders")).isFalse();
        assertThat(ResourceMatcher.matches("orders", null)).isFalse();
    }
}
