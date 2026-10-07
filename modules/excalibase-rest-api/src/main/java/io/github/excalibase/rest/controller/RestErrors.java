package io.github.excalibase.rest.controller;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;
import io.github.excalibase.rest.compiler.RestQueryCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The REST answer to a request that failed while it ran: the database's reason as a 4xx the client can act
 * on, or a 500 that says nothing more while the cause is logged here in full.
 */
final class RestErrors {

    private static final Logger log = LoggerFactory.getLogger(RestErrors.class);

    /** The value a Postgres input function quotes when it cannot read it: {@code ...: "value"}. */
    private static final Pattern QUOTED_VALUE = Pattern.compile(": \"(.*)\"$");

    private RestErrors() {
    }

    /**
     * @param filters the request's filters, to name the column a malformed value was meant for
     * @param serverFault what the 500 says when the failure is the server's own
     */
    static ResponseEntity<Object> of(Exception failure, SqlDialect dialect,
                                     List<RestQueryCompiler.FilterSpec> filters, String serverFault) {
        return DataErrors.describe(failure, dialect)
                .map(error -> attribute(error, filters))
                .map(RestErrors::response)
                .orElseGet(() -> {
                    log.error("rest_request_failed", failure);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", serverFault, "code", "internal_error"));
                });
    }

    static ResponseEntity<Object> response(DataError error) {
        return ResponseEntity.status(error.status()).body(error.restBody());
    }

    /** An invalid value names its column when exactly one filter carried that value. */
    private static DataError attribute(DataError error, List<RestQueryCompiler.FilterSpec> filters) {
        if (!DataError.INVALID_VALUE.equals(error.code()) || error.column() != null || filters.isEmpty()) {
            return error;
        }
        Matcher quoted = QUOTED_VALUE.matcher(error.message());
        if (!quoted.find()) {
            return error;
        }
        String value = quoted.group(1);
        List<String> columns = filters.stream()
                .filter(filter -> carries(filter, value))
                .map(RestQueryCompiler.FilterSpec::column)
                .distinct()
                .toList();
        return columns.size() == 1 ? error.withColumn(columns.getFirst()) : error;
    }

    private static boolean carries(RestQueryCompiler.FilterSpec filter, String value) {
        String raw = Objects.toString(filter.value(), "");
        String inner = raw.startsWith("(") && raw.endsWith(")") ? raw.substring(1, raw.length() - 1) : raw;
        return raw.equals(value) || Arrays.stream(inner.split(",")).map(String::trim).anyMatch(value::equals);
    }
}
