package io.github.excalibase.mongodb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.SessionVariable;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.spi.QueryEngine;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The permission document, not hand-written policies, decides what each role reads from a real MongoDB. */
class DocumentPermissionsEngineTest extends AbstractEngineTest {

    private static final String ORDERS = "orders";
    private static final String USER = "user";
    private static final String QUERY = "{ orders { id owner } }";
    private static final String OWNER = "owner";
    private static final String USER_ID_VARIABLE = "x-excalibase-user-id";
    private static final String WHERE = "$where";
    private static final String ID_KEY = "id";

    @Override
    Backend backend() {
        return Backend.MONGO;
    }

    private String table() {
        return database() + "." + ORDERS;
    }

    private static PermissionSet document(TablePermissions... tables) {
        return new PermissionSet("p1", 1, List.of(tables), List.of(), List.of());
    }

    private TablePermissions select(String role, BoolExp filter, ColumnSet columns) {
        return new TablePermissions(table(), role, Optional.of(new SelectPermission(filter, columns, null, false)),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private QueryEngine engineFrom(PermissionSet permissions) {
        return engineWith(Map.of("permissions", permissions));
    }

    private static UserContext caller(String role, String userId) {
        Map<String, String> variables = new HashMap<>();
        if (userId != null) {
            variables.put(USER_ID_VARIABLE, userId);
        }
        return new UserContext() {
            @Override public String userId() { return userId; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(role); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) { return variables.get(name); }
        };
    }

    private static ColumnCompare ownedByCaller() {
        return new ColumnCompare(OWNER, ComparisonOperator.EQ, new SessionVariable(USER_ID_VARIABLE));
    }

    @Test
    void aRoleReadsOnlyTheDocumentsItsFilterAllows() {
        QueryEngine engine = engineFrom(document(select(USER, ownedByCaller(), ColumnSet.ALL)));

        assertThat(rows(engine.execute(QUERY, Map.of(), caller(USER, "u1")), ORDERS)).extracting(r -> r.get(ID_KEY))
                .containsExactlyInAnyOrder(ID_1, ID_2);
        assertThat(rows(engine.execute(QUERY, Map.of(), caller(USER, "u2")), ORDERS)).extracting(r -> r.get(ID_KEY))
                .containsExactlyInAnyOrder(ID_3, ID_4);
    }

    @Test
    void aRoleTheDocumentDoesNotMentionReadsNothing() {
        QueryEngine engine = engineFrom(document(select(USER, ownedByCaller(), ColumnSet.ALL)));

        assertThat(rows(engine.execute(QUERY, Map.of(), caller("anon", null)), ORDERS)).isEmpty();
        assertThat(rows(engine.execute(QUERY, Map.of(), caller("stranger", "u1")), ORDERS)).isEmpty();
    }

    @Test
    void aCollectionWithNoSelectPermissionAnywhereReadsNothingForEveryone() {
        QueryEngine engine = engineFrom(document());

        assertThat(rows(engine.execute(QUERY, Map.of(), caller(USER, "u1")), ORDERS)).isEmpty();
    }

    @Test
    void aRequestWithoutTheSessionVariableAFilterNeedsFailsAndReturnsNoRows() {
        QueryEngine engine = engineFrom(document(select(USER, ownedByCaller(), ColumnSet.ALL)));

        Map<String, Object> response = engine.execute(QUERY, Map.of(), caller(USER, null));

        assertThat(error(response)).isNotNull();
        assertThat(rows(response, ORDERS)).isNullOrEmpty();
    }

    @Test
    void aColumnListRemovesTheOtherFieldsFromTheRolesSchema() {
        QueryEngine engine = engineFrom(document(select(USER, BoolExp.TRUE, ColumnSet.of(List.of("id", OWNER)))));

        Map<String, Object> allowed = engine.execute(QUERY, Map.of(), caller(USER, "u1"));
        Map<String, Object> denied = engine.execute("{ orders { id secret } }", Map.of(), caller(USER, "u1"));

        assertThat(rows(allowed, ORDERS)).hasSize(5);
        assertThat(error(denied)).isNotNull();
    }

    @Test
    void aLiteralThatIsAMongoOperatorNameIsOnlyAValue() {
        var filter = new ColumnCompare("status", ComparisonOperator.EQ, new Literal(WHERE));
        QueryEngine engine = engineFrom(document(select(USER, filter, ColumnSet.ALL)));

        assertThat(rows(engine.execute(QUERY, Map.of(), caller(USER, "u1")), ORDERS)).extracting(r -> r.get(ID_KEY))
                .containsExactly(ID_4);
    }

    @Test
    void anOrOfComparisonsReadsTheUnion() {
        var filter = new BoolExp.Or(List.of(new ColumnCompare(OWNER, ComparisonOperator.EQ, new Literal("u1")),
                new ColumnCompare("status", ComparisonOperator.EQ, new Literal(WHERE))));
        QueryEngine engine = engineFrom(document(select(USER, filter, ColumnSet.ALL)));

        assertThat(rows(engine.execute(QUERY, Map.of(), caller(USER, "u9")), ORDERS)).extracting(r -> r.get(ID_KEY))
                .containsExactlyInAnyOrder(ID_1, ID_2, ID_4);
    }

    @Test
    void aRuleThatCannotBeEnforcedStopsTheEngineFromStarting() {
        var unsupported = new BoolExp.Not(ownedByCaller());
        PermissionSet refused = document(select(USER, unsupported, ColumnSet.ALL));

        assertThatThrownBy(() -> engineFrom(refused))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("_not");
    }

    @Test
    void permissionsAndHandWrittenPoliciesCannotBeMixed() {
        Map<String, Object> both = Map.of("permissions", document(), "openCollections", List.of("*"));

        assertThatThrownBy(() -> engineWith(both))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("permissions");
    }

    @Test
    void aMissingSessionVariableIsRefusedWithoutAnErrorLinePerRequest() {
        QueryEngine engine = engineFrom(document(select(USER, ownedByCaller(), ColumnSet.ALL)));
        var logger = (Logger) LoggerFactory.getLogger(MongoQueryEngine.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            engine.execute(QUERY, Map.of(), caller(USER, null));
        } finally {
            logger.detachAppender(captured);
        }

        assertThat(captured.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    void theEngineAnswersAPingWhenTheDatabaseDoes() {
        QueryEngine engine = engineFrom(document(select(USER, BoolExp.TRUE, ColumnSet.ALL)));

        assertThat(engine.healthy()).isTrue();
    }
}
