package io.github.excalibase.nats;

import io.nats.client.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NatsOptionsFactoryTest {

    private static final String URL = "nats://localhost:4222";
    private static final String PRINCIPAL = "svc-graphql";

    @Test
    void anonymousConnectionCarriesNoCredential() {
        Options options = NatsOptionsFactory.builder(URL, "", "", "").build();

        assertNull(options.getUsername(), "blank username must not set a credential");
        assertEquals(-1, options.getMaxReconnect());
    }

    @Test
    void credentialIsApplied() {
        Options options = NatsOptionsFactory.builder(URL, PRINCIPAL, "pw", "").build();

        assertEquals(PRINCIPAL, new String(options.getUsernameChars()));
        assertEquals("pw", new String(options.getPasswordChars()));
    }

    @Test
    void inboxPrefixIsApplied() {
        Options options = NatsOptionsFactory.builder(URL, PRINCIPAL, "pw", "_INBOX_svc-graphql").build();

        assertTrue(options.getInboxPrefix().startsWith("_INBOX_svc-graphql"),
                "inbox prefix = " + options.getInboxPrefix());
    }

    @Test
    void blankInboxPrefixKeepsTheClientDefault() {
        Options withPrefix = NatsOptionsFactory.builder(URL, PRINCIPAL, "pw", "_INBOX_svc-graphql").build();
        Options withoutPrefix = NatsOptionsFactory.builder(URL, PRINCIPAL, "pw", "  ").build();

        assertNotEquals(withPrefix.getInboxPrefix(), withoutPrefix.getInboxPrefix());
    }
}
