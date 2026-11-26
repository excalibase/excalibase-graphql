package io.github.excalibase.document;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The document engine's start-up refusals and its follow of the permission document, with no database behind it. */
class DocumentEnginesTest {

    private static final String PROJECT = "p1";
    private static final String QUERY = "{ __typename }";
    private static Path collections;

    @BeforeAll
    static void declare() throws IOException {
        collections = Files.createTempFile("collections", ".yaml");
        Files.writeString(collections, "collections:\n  - name: orders\n    fields:\n      owner: string\n");
    }

    private static PermissionSet document(long version, BoolExp filter) {
        var select = new SelectPermission(filter, ColumnSet.ALL, null, false);
        return new PermissionSet(PROJECT, version, List.of(new TablePermissions("app.orders", "user", Optional.of(select),
                Optional.empty(), Optional.empty(), Optional.empty())), List.of(), List.of());
    }

    private static PermissionProvider serving(AtomicReference<PermissionSet> held) {
        return new PermissionProvider() {
            @Override public PermissionSet permissionsFor(String projectId) { return held.get(); }
            @Override public void evict(String projectId) { }
        };
    }

    private static DocumentEngines engines(PermissionProvider permissions, String projectId, String collectionsFile) {
        return new DocumentEngines("mongodb", "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=100", "app",
                collectionsFile, 1000, 30, projectId, permissions);
    }

    private static Principal user() {
        return new Principal("user", false, null, Map.of());
    }

    @Test
    void withoutAPermissionSourceItRefusesToStart() {
        assertThatThrownBy(() -> engines(null, PROJECT, collections.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("permission document");
        assertThatThrownBy(() -> engines(new NoPermissionSource(), PROJECT, collections.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("permission document");
    }

    @Test
    void withoutAProjectOrCollectionsItRefusesToStart() {
        AtomicReference<PermissionSet> held = new AtomicReference<>(document(1, BoolExp.TRUE));

        assertThatThrownBy(() -> engines(serving(held), "", collections.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.project-id");
        assertThatThrownBy(() -> engines(serving(held), PROJECT, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collections");
    }

    @Test
    void aDocumentWithARuleItCannotEnforceStopsTheStart() {
        var unsupported = new BoolExp.Not(new ColumnCompare("owner", ComparisonOperator.EQ, new Literal("x")));
        AtomicReference<PermissionSet> held = new AtomicReference<>(document(1, unsupported));
        DocumentEngines refused = engines(serving(held), PROJECT, collections.toString());

        assertThatThrownBy(refused::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("_not");
    }

    @Test
    void aNewDocumentVersionReplacesTheEngineAndARefusedOneAnswersUnavailable() {
        AtomicReference<PermissionSet> held = new AtomicReference<>(document(1, BoolExp.TRUE));
        try (DocumentEngines engines = engines(serving(held), PROJECT, collections.toString())) {
            assertThat(engines.execute(QUERY, Map.of(), user())).containsKey("data");

            var unsupported = new BoolExp.Not(new ColumnCompare("owner", ComparisonOperator.EQ, new Literal("x")));
            held.set(document(2, unsupported));

            assertThatThrownBy(() -> engines.execute(QUERY, Map.of(), user()))
                    .isInstanceOf(PermissionsUnavailableException.class);

            held.set(document(3, BoolExp.TRUE));
            assertThat(engines.execute(QUERY, Map.of(), user())).containsKey("data");
        }
    }

    @Test
    void aNewDocumentVersionClosesTheEngineItReplacesAndShutdownClosesTheLast() {
        AtomicReference<PermissionSet> held = new AtomicReference<>(document(1, BoolExp.TRUE));
        int openBefore = CountingProvider.OPEN.get();
        int builtBefore = CountingProvider.BUILT.get();
        DocumentEngines engines = new DocumentEngines(CountingProvider.TYPE, "", "app", collections.toString(), 1000, 30,
                PROJECT, serving(held));

        engines.execute(QUERY, Map.of(), user());
        engines.execute(QUERY, Map.of(), user());
        assertThat(CountingProvider.BUILT.get() - builtBefore).as("same version, one engine").isEqualTo(1);

        held.set(document(2, BoolExp.TRUE));
        engines.execute(QUERY, Map.of(), user());
        assertThat(CountingProvider.BUILT.get() - builtBefore).isEqualTo(2);
        assertThat(CountingProvider.OPEN.get() - openBefore).as("the replaced engine is closed").isEqualTo(1);

        engines.close();
        assertThat(CountingProvider.OPEN.get() - openBefore).as("shutdown closes the last").isZero();
    }
}
