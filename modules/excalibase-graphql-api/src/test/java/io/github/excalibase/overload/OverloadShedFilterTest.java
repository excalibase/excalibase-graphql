package io.github.excalibase.overload;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OverloadShedFilterTest {

    private static final HeapPressure CALM = () -> false;
    private static final HeapPressure FULL = () -> true;

    private static OverloadShedFilter filter(int permits, Duration wait, HeapPressure heap) {
        return new OverloadShedFilter(new InFlightLimiter(permits, wait), heap, 3);
    }

    private static MockHttpServletResponse call(OverloadShedFilter filter, String uri, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a calm server passes requests through")
    void calm_passesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = call(filter(2, Duration.ZERO, CALM), "/p/graphql", chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("heap pressure sheds with 503, Retry-After and a GraphQL error body without internals")
    void heapPressure_sheds() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = call(filter(2, Duration.ZERO, FULL), "/p/api/v1/orders", chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"errors\":[{\"message\":\"Server overloaded, retry shortly\","
                        + "\"extensions\":{\"code\":\"overloaded\"}}]}");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("health and actuator paths are never shed")
    void actuator_neverShed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = call(filter(1, Duration.ZERO, FULL), "/actuator/health", chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("websocket upgrades are not counted against the in-flight cap")
    void websocketUpgrade_notCounted() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/p/api/v1/realtime");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(1, Duration.ZERO, CALM).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("past the in-flight cap a request is shed after the bounded wait, and permits are released")
    void inFlightCap_shedsAndReleases() throws Exception {
        OverloadShedFilter filter = filter(1, Duration.ofMillis(50), CALM);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocking = (req, res) -> {
            entered.countDown();
            awaitQuietly(release);
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MockHttpServletResponse> first = pool.submit(() -> call(filter, "/p/graphql", blocking));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse shed = call(filter, "/p/graphql", chain);
            assertThat(shed.getStatus()).isEqualTo(503);
            verify(chain, never()).doFilter(any(), any());

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(call(filter, "/p/graphql", mock(FilterChain.class)).getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the permit is released when the chain throws")
    void permitReleasedOnException() throws Exception {
        OverloadShedFilter filter = filter(1, Duration.ZERO, CALM);
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try {
            call(filter, "/p/graphql", failing);
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }
        assertThat(call(filter, "/p/graphql", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    }
}
