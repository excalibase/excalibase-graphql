package io.github.excalibase.access;

import io.github.excalibase.permissions.PermissionSet;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.github.excalibase.access.AccessFixture.CUSTOMERS;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.ORDER_VIEW;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.selectAll;
import static io.github.excalibase.permissions.compile.PermissionDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/** What the engine would silently drop from a document, listed so a person can be told before it is saved. */
class RuleProblemsTest {

    private static List<RuleProblem> problems(String... entries) {
        PermissionSet document = AccessFixture.document(List.of(entries), List.of(), List.of());
        return RuleProblems.find(POSTGRES, AccessFixture.schema(), document);
    }

    @Test
    void aDocumentThatFitsTheSchemaHasNoProblems() {
        assertThat(problems(entry(ORDERS, selectAll()), entry(CUSTOMERS, selectAll()))).isEmpty();
    }

    @Test
    void aRuleForATableTheDatabaseLacksIsReported() {
        List<RuleProblem> found = problems(entry("public.ghosts", selectAll()));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.role()).isEqualTo("user");
            assertThat(problem.table()).isEqualTo("public.ghosts");
            assertThat(problem.operation()).isEqualTo("table");
            assertThat(problem.code()).isEqualTo("unknown_table");
        });
    }

    @Test
    void anUnknownColumnInTheColumnListNamesTheOperationAndTheColumn() {
        List<RuleProblem> found = problems(entry(ORDERS,
                "\"select\":{\"filter\":{},\"columns\":[\"id\",\"ghost\"]}"));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.operation()).isEqualTo("select");
            assertThat(problem.code()).isEqualTo("unknown_column");
            assertThat(problem.reason()).contains("ghost");
        });
    }

    @Test
    void anUnknownColumnInAFilterOrACheckOrAPresetIsReportedAndTheWritesWithoutAReadableSelectToo() {
        List<RuleProblem> found = problems(entry(ORDERS,
                "\"select\":{\"filter\":{\"ghost\":{\"_eq\":1}},\"columns\":\"*\"}"
                        + ",\"insert\":{\"check\":{\"ghost\":{\"_eq\":1}},\"columns\":\"*\",\"set\":{}}"
                        + ",\"update\":{\"filter\":{},\"check\":{},\"columns\":\"*\",\"set\":{\"ghost\":1}}"
                        + ",\"delete\":{\"filter\":{\"ghost\":{\"_eq\":1}}}"));

        assertThat(found).extracting(RuleProblem::operation).contains("select", "insert");
        assertThat(found).filteredOn(problem -> problem.code().equals("unknown_column"))
                .extracting(RuleProblem::operation).containsExactlyInAnyOrder("select", "insert");
        assertThat(found).filteredOn(problem -> problem.code().equals("needs_select"))
                .extracting(RuleProblem::operation).containsExactlyInAnyOrder("update", "delete");
    }

    @Test
    void anInsertWithoutASelectAlsoReportsTheDroppedUpdate() {
        List<RuleProblem> found = problems(entry(ORDERS, "\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{}}"
                + ",\"update\":{\"filter\":{},\"check\":{},\"columns\":\"*\",\"set\":{}}"));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.operation()).isEqualTo("update");
            assertThat(problem.code()).isEqualTo("needs_select");
        });
    }

    @Test
    void everyRoleInTheDocumentIsChecked() {
        PermissionSet document = AccessFixture.document(List.of(
                "{\"table\":\"public.ghosts\",\"role\":\"anon\",\"select\":{\"filter\":{},\"columns\":\"*\"}}",
                "{\"table\":\"public.ghosts\",\"role\":\"staff\",\"select\":{\"filter\":{},\"columns\":\"*\"}}"),
                List.of(), List.of());

        assertThat(RuleProblems.find(POSTGRES, AccessFixture.schema(), document)).extracting(RuleProblem::role)
                .containsExactlyInAnyOrder("anon", "staff");
    }

    @Test
    void aTableOfAnotherSchemaIsNotTheTableOfTheSameName() {
        List<RuleProblem> found = problems(entry("archive.orders", selectAll()), entry("other.customers", selectAll()));

        assertThat(found).extracting(RuleProblem::table).containsExactly("archive.orders", "other.customers");
        assertThat(found).extracting(RuleProblem::code).containsOnly("unknown_table");
    }

    @Test
    void writeRulesOnAViewAreReportedBecauseTheEngineIgnoresThem() {
        List<RuleProblem> found = problems(entry(ORDER_VIEW, selectAll() + ",\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{}}"
                + ",\"update\":{\"filter\":{},\"check\":{},\"columns\":\"*\",\"set\":{}}"
                + ",\"delete\":{\"filter\":{}}"));

        assertThat(found).extracting(RuleProblem::operation).containsExactlyInAnyOrder("insert", "update", "delete");
        assertThat(found).extracting(RuleProblem::code).containsOnly("not_writable");
    }

    @Test
    void aReadRuleOnAViewIsFine() {
        assertThat(problems(entry(ORDER_VIEW, selectAll()))).isEmpty();
    }

    @Test
    void inspectingWritesNothingToTheLog() {
        Logger resolverLog = (Logger) LoggerFactory.getLogger(RulesResolver.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        resolverLog.addAppender(logged);
        try {
            problems(entry("public.ghosts", selectAll()), entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"ghost\"]}"));
        } finally {
            resolverLog.detachAppender(logged);
        }

        assertThat(logged.list).isEmpty();
    }

    @Test
    void buildingTheRulesOfARoleStillLogsWhatItDrops() {
        Logger resolverLog = (Logger) LoggerFactory.getLogger(RulesResolver.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        resolverLog.addAppender(logged);
        try {
            RulesResolver.resolve(POSTGRES, AccessFixture.schema(), AccessFixture.user(entry("public.ghosts", selectAll())));
        } finally {
            resolverLog.detachAppender(logged);
        }

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString().contains("permission_invalid", "public.ghosts");
    }
}
