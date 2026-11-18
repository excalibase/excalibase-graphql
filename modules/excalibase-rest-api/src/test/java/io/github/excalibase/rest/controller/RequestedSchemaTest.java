package io.github.excalibase.rest.controller;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaProvider;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestedSchemaTest {

    private static final SchemaProvider PROVIDER = new SchemaProvider() {
        @Override
        public SchemaInfo resolveSchemaInfo(Principal principal) {
            return new SchemaInfo();
        }

        @Override
        public SqlDialect resolveDialect(Principal principal) {
            return null;
        }

        @Override
        public String resolveDefaultSchema(Principal principal) {
            return "public";
        }
    };

    @Test
    void aProfileNamingAnInternalSchema_resolvesToNothing() {
        assertThat(RequestedSchema.resolve(PROVIDER, "excalibase_meta", Principal.anonymous())).isNull();
        assertThat(RequestedSchema.resolve(PROVIDER, " AUTH ", Principal.anonymous())).isNull();
        assertThat(RequestedSchema.resolve(PROVIDER, "excalibase", Principal.anonymous())).isNull();
    }

    @Test
    void aNormalProfile_orNone_resolvesAsBefore() {
        assertThat(RequestedSchema.resolve(PROVIDER, "sales", Principal.anonymous())).isEqualTo("sales");
        assertThat(RequestedSchema.resolve(PROVIDER, null, Principal.anonymous())).isEqualTo("public");
        assertThat(RequestedSchema.resolve(PROVIDER, "  ", Principal.anonymous())).isEqualTo("public");
    }

    @Test
    void aMalformedProfile_isRefused() {
        assertThatThrownBy(() -> RequestedSchema.resolve(PROVIDER, "a;b", Principal.anonymous()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
