package io.github.excalibase.nosql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

@Service
public class DocumentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExecutionService.class);

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;

    public DocumentExecutionService(NamedParameterJdbcTemplate namedJdbc, ObjectMapper mapper) {
        this.namedJdbc = namedJdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> executeQuery(DocumentQueryCompiler.CompiledDoc compiled) {
        var params = new MapSqlParameterSource(compiled.params());
        return namedJdbc.query(compiled.sql(), params, (rs, rowNum) -> {
            var doc = parseJsonb(rs.getString("data"));
            doc.put("id", rs.getString("id"));
            doc.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            doc.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return doc;
        });
    }

    public Optional<Map<String, Object>> executeSingleQuery(DocumentQueryCompiler.CompiledDoc compiled) {
        var results = executeQuery(compiled);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Map<String, Object> executeMutation(DocumentQueryCompiler.CompiledDoc compiled) {
        var params = new MapSqlParameterSource(compiled.params());
        return namedJdbc.queryForObject(compiled.sql(), params, (rs, rowNum) -> {
            var doc = parseJsonb(rs.getString("data"));
            doc.put("id", rs.getString("id"));
            doc.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            doc.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return doc;
        });
    }

    public List<Map<String, Object>> executeBulkMutation(DocumentQueryCompiler.CompiledDoc compiled) {
        // Bulk INSERT/UPDATE/DELETE with RETURNING produces a result set identical to a SELECT.
        return executeQuery(compiled);
    }

    public long executeCount(DocumentQueryCompiler.CompiledDoc compiled) {
        var params = new MapSqlParameterSource(compiled.params());
        var result = namedJdbc.queryForObject(compiled.sql(), params, Long.class);
        return result != null ? result : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonb(String json) {
        try {
            return new LinkedHashMap<>(mapper.readValue(json, Map.class));
        } catch (Exception e) {
            log.warn("Failed to parse JSONB document — returning empty map", e);
            return new LinkedHashMap<>();
        }
    }

    private String formatTimestamp(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }
}
