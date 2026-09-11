package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PolicyChangeSubscriber}. A message on
 * {@code policies.{projectId}.changed} must evict exactly that project's cached
 * policies; any malformed subject must be ignored (no evict, no throw) so a
 * stray publish can never fan out to the wrong project or crash the handler.
 * The TTL stays the fail-safe, so dropping an invalidation is acceptable —
 * acting on a bad one is not.
 */
@ExtendWith(MockitoExtension.class)
class PolicyChangeSubscriberTest {

    @Mock
    private PolicyProvider policyProvider;

    private PolicyChangeSubscriber subscriber() {
        return new PolicyChangeSubscriber(policyProvider, false, "nats://localhost:4222");
    }

    @Test
    @DisplayName("well-formed subject evicts exactly that project once")
    void evictFor_wellFormedSubject_evictsProjectOnce() {
        subscriber().evictFor("policies.proj-abc.changed");

        verify(policyProvider, times(1)).evict("proj-abc");
    }

    @Test
    @DisplayName("subject missing the project token is ignored")
    void evictFor_missingProjectToken_noEvict() {
        subscriber().evictFor("policies.changed");

        verify(policyProvider, never()).evict(any());
    }

    @Test
    @DisplayName("subject with an empty project token is ignored")
    void evictFor_emptyProjectToken_noEvict() {
        subscriber().evictFor("policies..changed");

        verify(policyProvider, never()).evict(any());
    }

    @Test
    @DisplayName("wildcard project token is never evicted")
    void evictFor_wildcardProjectToken_noEvict() {
        subscriber().evictFor("policies.*.changed");

        verify(policyProvider, never()).evict(any());
    }

    @Test
    @DisplayName("blank project token is ignored")
    void evictFor_blankProjectToken_noEvict() {
        subscriber().evictFor("policies.   .changed");

        verify(policyProvider, never()).evict(any());
    }

    @Test
    @DisplayName("null subject does not throw and does not evict")
    void evictFor_nullSubject_noThrow() {
        PolicyChangeSubscriber subscriber = subscriber();

        assertThatCode(() -> subscriber.evictFor(null)).doesNotThrowAnyException();
        verify(policyProvider, never()).evict(any());
    }

    @Test
    @DisplayName("parseProjectId extracts the 2nd subject token")
    void parseProjectId_extractsSecondToken() {
        assertThat(PolicyChangeSubscriber.parseProjectId("policies.proj-123.changed"))
                .isEqualTo("proj-123");
        assertThat(PolicyChangeSubscriber.parseProjectId("policies.changed")).isNull();
        assertThat(PolicyChangeSubscriber.parseProjectId("policies..changed")).isNull();
        assertThat(PolicyChangeSubscriber.parseProjectId("policies.*.changed")).isNull();
        assertThat(PolicyChangeSubscriber.parseProjectId("other.proj.changed")).isNull();
    }

    @Test
    @DisplayName("start() is a safe no-op when NATS is disabled")
    void start_natsDisabled_noConnectAttempt() {
        PolicyChangeSubscriber subscriber =
                new PolicyChangeSubscriber(policyProvider, false, "nats://unreachable:4222");

        assertThatCode(subscriber::start).doesNotThrowAnyException();
        assertThat(subscriber.isRunning()).isFalse();
    }
}
