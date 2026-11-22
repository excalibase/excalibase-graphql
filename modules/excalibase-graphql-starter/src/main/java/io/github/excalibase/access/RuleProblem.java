package io.github.excalibase.access;

/**
 * One rule the engine drops because the database does not fit it: dropping denies, so the role holds less than the
 * document says.
 *
 * @param operation {@code table} for a rule about a table the database lacks, otherwise select, insert, update or delete
 * @param code      a {@code PermissionEvaluationException} code, or {@code needs_select}
 */
public record RuleProblem(String role, String table, String operation, String code, String reason) {
}
