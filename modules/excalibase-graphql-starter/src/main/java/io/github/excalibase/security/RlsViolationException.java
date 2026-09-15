package io.github.excalibase.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thrown when a write's candidate row fails the RLS WITH-CHECK — the caller is
 * attempting to write a row no policy permits them to. Unchecked so it
 * propagates out of the compiler; the GraphQL/REST error handlers surface it as
 * a typed {@value #CODE} error carrying only the operation, the table and (when
 * known) the policy name — never the SQL nor column values.
 */
public class RlsViolationException extends RuntimeException {

    public static final String CODE = "RLS_DENIED";
    public static final String OPERATION_UPSERT = "UPSERT";

    private final String operation;
    private final String table;
    private final String policy;

    public RlsViolationException(String operation, String table) {
        this(operation, table, null);
    }

    public RlsViolationException(String operation, String table, String policy) {
        super("Row-level security denied " + operation + " on " + table);
        this.operation = operation;
        this.table = table;
        this.policy = policy;
    }

    public String code() {
        return CODE;
    }

    public String operation() {
        return operation;
    }

    public String table() {
        return table;
    }

    public Optional<String> policy() {
        return Optional.ofNullable(policy);
    }

    /** Client-safe context: operation, table and the policy name when known. */
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", operation);
        details.put("table", table);
        if (policy != null) {
            details.put("policy", policy);
        }
        return Collections.unmodifiableMap(details);
    }

    /** Finds an RLS denial anywhere in a cause chain (executors and JDBC wrap it). */
    public static Optional<RlsViolationException> find(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RlsViolationException denied) {
                return Optional.of(denied);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }
}
