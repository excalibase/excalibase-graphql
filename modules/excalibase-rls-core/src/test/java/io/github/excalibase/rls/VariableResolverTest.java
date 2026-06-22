package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableResolverTest {

    private static final UUID UID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final UserContext ctx = new TestUserContext(
        UID.toString(),
        TID.toString(),
        Set.of("authenticated", "admin"),
        Set.of("g1", "g2"),
        name -> "departmentId".equals(name) ? UUID.fromString("33333333-3333-3333-3333-333333333333") : null
    );

    private final VariableResolver resolver = new VariableResolver(ctx);

    @Test
    @DisplayName("literal value cast to declared field type")
    void resolve_literalValue_castToFieldType() {
        assertThat(resolver.resolve("42", FieldType.INTEGER)).isEqualTo(42);
        assertThat(resolver.resolve("true", FieldType.BOOLEAN)).isEqualTo(true);
        assertThat(resolver.resolve(UID.toString(), FieldType.UUID)).isEqualTo(UID);
        assertThat(resolver.resolve("hello", FieldType.STRING)).isEqualTo("hello");
    }

    @Test
    @DisplayName("{{currentUserId}} resolves to UserContext.userId() as UUID")
    void resolve_currentUserId_returnsUuid() {
        Object resolved = resolver.resolve("{{currentUserId}}", FieldType.UUID);
        assertThat(resolved).isEqualTo(UID);
    }

    @Test
    @DisplayName("{{currentUserId}}/{{currentTenantId}} bind per declared fieldType — non-UUID ids work")
    void resolve_identity_respectsNonUuidFieldType() {
        VariableResolver r = new VariableResolver(
            new TestUserContext("1", "e2e-test", Set.of(), Set.of(), name -> null));
        assertThat(r.resolve("{{currentUserId}}", FieldType.STRING)).isEqualTo("1");
        assertThat(r.resolve("{{currentUserId}}", FieldType.INTEGER)).isEqualTo(1);
        assertThat(r.resolve("{{currentTenantId}}", FieldType.STRING)).isEqualTo("e2e-test");
    }

    @Test
    @DisplayName("{{currentTenantId}} resolves to UserContext.tenantId() as UUID")
    void resolve_currentTenantId_returnsUuid() {
        assertThat(resolver.resolve("{{currentTenantId}}", FieldType.UUID)).isEqualTo(TID);
    }

    @Test
    @DisplayName("{{currentUserRoles}} resolves to UserContext.roles() set")
    void resolveList_currentUserRoles_returnsSet() {
        Collection<Object> resolved = resolver.resolveList("{{currentUserRoles}}", FieldType.STRING);
        assertThat(resolved).containsExactlyInAnyOrderElementsOf(List.of("authenticated", "admin"));
    }

    @Test
    @DisplayName("{{currentUserGroupIds}} resolves to UserContext.groupIds() set")
    void resolveList_currentUserGroupIds_returnsSet() {
        Collection<Object> resolved = resolver.resolveList("{{currentUserGroupIds}}", FieldType.STRING);
        assertThat(resolved).containsExactlyInAnyOrderElementsOf(List.of("g1", "g2"));
    }

    @Test
    @DisplayName("comma-separated literal for IN operator")
    void resolveList_commaSeparated_castEach() {
        Collection<Object> resolved = resolver.resolveList("active,pending,paid", FieldType.STRING);
        assertThat(resolved).containsExactlyElementsOf(List.of("active", "pending", "paid"));
    }

    @Test
    @DisplayName("{{now}} returns the same Instant across a single resolution batch")
    void resolve_now_isStableWithinBatch() {
        Object a = resolver.resolve("{{now}}", FieldType.DATETIME);
        Object b = resolver.resolve("{{now}}", FieldType.DATETIME);
        assertThat(a).isInstanceOf(Instant.class).isEqualTo(b);
    }

    @Test
    @DisplayName("{{today}} returns a LocalDate")
    void resolve_today_returnsLocalDate() {
        assertThat(resolver.resolve("{{today}}", FieldType.DATE)).isInstanceOf(LocalDate.class);
    }

    @Test
    @DisplayName("{{daysAgo:30}} returns Instant 30 days before now")
    void resolve_daysAgo_subtractsDays() {
        Object resolved = resolver.resolve("{{daysAgo:30}}", FieldType.DATETIME);
        assertThat(resolved).isInstanceOf(Instant.class);
        Instant thirty = (Instant) resolved;
        long days = ChronoUnit.DAYS.between(thirty, Instant.now());
        assertThat(days).isBetween(29L, 31L);
    }

    @Test
    @DisplayName("custom variable resolved via UserContext.resolveVariable()")
    void resolve_customVariable_delegatesToContext() {
        Object resolved = resolver.resolve("{{departmentId}}", FieldType.UUID);
        assertThat(resolved).isEqualTo(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    @DisplayName("unknown variable throws — typos are authoring bugs, not silent matches")
    void resolve_unknownVariable_throws() {
        assertThatThrownBy(() -> resolver.resolve("{{nope}}", FieldType.STRING))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("null value resolves to null")
    void resolve_nullValue_returnsNull() {
        assertThat(resolver.resolve(null, FieldType.STRING)).isNull();
    }

    private record TestUserContext(
        String userId, String tenantId, Set<String> roles, Set<String> groupIds,
        java.util.function.Function<String, Object> customResolver
    ) implements UserContext {
        @Override
        public Object resolveVariable(String name) {
            return customResolver.apply(name);
        }
    }
}
