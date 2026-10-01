package io.github.excalibase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.excalibase.compiler.MutationSequence;
import io.github.excalibase.utils.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a {@link MutationSequence} on one connection, in the caller's transaction: every field in order,
 * and each nested insert statement by statement in Hasura's order, binding the rows each statement wrote
 * into the statements that hang off them. Answers the operation's {@code data} object as JSON.
 */
final class MutationSequenceRunner {

    /** Rows pass through as JSON; numbers keep every digit. */
    private static final ObjectMapper ROWS = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private final Connection connection;
    private final Map<String, Object> params;

    MutationSequenceRunner(Connection connection, Map<String, Object> params) {
        this.connection = connection;
        this.params = new HashMap<>(params);
    }

    String run(MutationSequence sequence) throws SQLException, JsonProcessingException {
        StringBuilder data = new StringBuilder("{");
        for (MutationSequence.Step step : sequence.steps()) {
            String value = switch (step) {
                case MutationSequence.Statement statement -> value(statement.sql());
                case MutationSequence.NestedInsert nested -> nestedInsert(nested);
            };
            data.append(data.length() > 1 ? "," : "").append(ROWS.writeValueAsString(step.responseKey()))
                    .append(':').append(value);
        }
        return data.append('}').toString();
    }

    private String nestedInsert(MutationSequence.NestedInsert nested) throws SQLException, JsonProcessingException {
        ArrayNode rows = ROWS.createArrayNode();
        int written = 0;
        for (MutationSequence.InsertNode root : nested.roots()) {
            Written result = insert(root, null);
            rows.addAll((ArrayNode) ROWS.readTree(result.rows()));
            written += result.total();
        }
        params.put(nested.rowsParam(), rows.toString());
        params.put(nested.countParam(), written);
        return value(nested.outputSql());
    }

    /**
     * What one statement wrote ({@code own} rows, as {@code rows}), and how many rows it and everything
     * hanging off it wrote ({@code total}).
     */
    private record Written(int total, int own, String rows) {
    }

    private Written insert(MutationSequence.InsertNode node, String parentRows) throws SQLException, JsonProcessingException {
        if (node.parentRowParam() != null) {
            params.put(node.parentRowParam(), parentRows);
        }
        int count = 0;
        for (MutationSequence.Before before : node.before()) {
            Written target = insert(before.node(), null);
            if (target.own() != 1) {
                throw new IllegalArgumentException("cannot proceed to insert object relation \""
                        + before.relationship() + "\" since insert to table \"" + before.node().table()
                        + "\" affects zero rows");
            }
            params.put(before.rowParam(), target.rows());
            count += target.total();
        }
        Written own = statement(node.sql());
        count += own.own();
        if (!node.after().isEmpty() && own.own() != 1) {
            throw new IllegalArgumentException("cannot proceed to insert array relations since insert to table \""
                    + node.table() + "\" affects zero rows");
        }
        for (MutationSequence.InsertNode child : node.after()) {
            count += insert(child, own.rows()).total();
        }
        return new Written(count, own.own(), own.rows());
    }

    private Written statement(String sql) throws SQLException {
        return query(sql, result -> {
            result.next();
            int written = result.getInt(1);
            return new Written(written, written, result.getString(2));
        });
    }

    /** The single value a statement answers, as JSON; {@code null} when it answers no row. */
    private String value(String sql) throws SQLException {
        return query(sql, result -> {
            String json = result.next() ? result.getString(1) : null;
            return json == null ? "null" : json;
        });
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet result) throws SQLException;
    }

    private <T> T query(String sql, ResultReader<T> reader) throws SQLException {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(sql, params);
        try (PreparedStatement statement = connection.prepareStatement(resolved.sql())) {
            for (int i = 0; i < resolved.values().size(); i++) {
                statement.setObject(i + 1, resolved.values().get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                return reader.read(result);
            }
        }
    }
}
