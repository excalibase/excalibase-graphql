package io.github.excalibase.sqlite;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteConnectionPropertiesTest {

    @Test
    void readsTakeNoLock_andAMissingFileIsNotCreated() {
        assertThat(SqliteConnectionProperties.defaults(null))
                .containsEntry("transaction_mode", "DEFERRED")
                .containsEntry("open_mode", "2")
                .containsEntry("journal_mode", "WAL")
                .containsEntry("synchronous", "NORMAL");
    }

    @Test
    void durabilityCanBeRaisedToFull() {
        assertThat(SqliteConnectionProperties.defaults("full")).containsEntry("synchronous", "FULL");
    }

    @Test
    void anUnknownDurabilityIsRefused() {
        assertThatThrownBy(() -> SqliteConnectionProperties.defaults("sometimes"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SQLITE_SYNCHRONOUS");
    }
}
