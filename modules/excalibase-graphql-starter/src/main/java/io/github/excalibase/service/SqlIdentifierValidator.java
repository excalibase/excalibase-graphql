package io.github.excalibase.service;

import java.util.regex.Pattern;

/**
 * Validates SQL identifiers (table names, column names) to prevent injection.
 *
 * <p>Table and column names in this project come from database schema reflection
 * (pg_catalog / information_schema), not from user input. This validator provides
 * defense-in-depth: if an identifier somehow contains malicious content, it will
 * be rejected before reaching any SQL query.</p>
 */
public final class SqlIdentifierValidator {

    /**
     * Valid SQL identifiers: letters, digits, underscores, spaces, hyphens, dots.
     * Covers standard names and PostgreSQL-style quoted identifiers with spaces.
     */
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_ .\\-]*");

    private SqlIdentifierValidator() {
    }

    /**
     * Validates that the given name is a safe SQL identifier.
     *
     * @param identifier the table or column name to validate
     * @return the identifier unchanged if valid
     * @throws IllegalArgumentException if the identifier contains unsafe characters
     */
    public static String validate(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier must not be null or empty");
        }
        if (!VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }

    /**
     * Quotes an identifier for MySQL using backticks and escapes embedded backticks.
     *
     * @param identifier the identifier to quote
     * @return the quoted identifier, e.g. {@code `my_table`}
     */
    public static String quoteMysql(String identifier) {
        validate(identifier);
        return "`" + identifier.replace("`", "``") + "`";
    }

    /**
     * Quotes an identifier for PostgreSQL using double quotes and escapes embedded quotes.
     *
     * @param identifier the identifier to quote
     * @return the quoted identifier, e.g. {@code "my_table"}
     */
    public static String quotePostgres(String identifier) {
        validate(identifier);
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
