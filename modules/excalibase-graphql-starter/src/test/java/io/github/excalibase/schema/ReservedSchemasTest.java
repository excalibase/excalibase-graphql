package io.github.excalibase.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedSchemasTest {

    @Test
    void theBuiltInSchemasAreReserved_andConfigurationCannotRemoveThem() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig("billing");

        assertThat(reserved.isReserved("excalibase_meta")).isTrue();
        assertThat(reserved.isReserved("auth")).isTrue();
        assertThat(reserved.isReserved("excalibase")).isTrue();
        assertThat(reserved.isReserved("billing")).isTrue();
        assertThat(reserved.isReserved("public")).isFalse();
    }

    @Test
    void aQualifiedName_isReservedByItsSchema_whateverTheCaseOrSpacing() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig(null);

        assertThat(reserved.isReservedObject("excalibase_meta.t")).isTrue();
        assertThat(reserved.isReservedObject("AUTH.users")).isTrue();
        assertThat(reserved.isReservedObject(" auth .users")).isTrue();
        assertThat(reserved.isReservedObject("public.t")).isFalse();
        assertThat(reserved.isReservedObject("auth_events.t")).isFalse();
    }

    @Test
    void aNameWithNoSchema_orNoName_isNotReservedHere() {
        ReservedSchemas reserved = ReservedSchemas.fromConfig(null);

        assertThat(reserved.isReservedObject("auth")).isFalse();
        assertThat(reserved.isReservedObject(null)).isFalse();
        assertThat(reserved.isReservedObject("")).isFalse();
    }
}
