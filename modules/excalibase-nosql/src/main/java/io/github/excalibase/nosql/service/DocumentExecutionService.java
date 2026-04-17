package io.github.excalibase.nosql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

@Service
public class DocumentExecutionService {

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

    public Map<String, Object> executeSingleQuery(DocumentQueryCompiler.CompiledDoc compiled) {
        var results = executeQuery(compiled);
        return results.isEmpty() ? null : results.getFirst();
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
        var params = new MapSqlParameterSource(compiled.params());
        return namedJdbc.query(compiled.sql(), params, (rs, rowNum) -> {
            var doc = parseJsonb(rs.getString("data"));
            doc.put("id", rs.getString("id"));
            doc.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            doc.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return doc;
        });
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
            return new LinkedHashMap<>();
        }
    }

    private String formatTimestamp(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }
}
