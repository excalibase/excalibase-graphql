package io.github.excalibase.mysql.fetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.fetcher.IDatabaseDataFetcher;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL implementation of {@link IDatabaseDataFetcher}.
 *
 * <p>Uses backtick-quoted identifiers and {@link JdbcTemplate} for all queries.
 * The schema reflector is resolved lazily via {@link ServiceLookup} using the MySQL
 * service name, exactly like the Postgres implementation.</p>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlDatabaseDataFetcherImplement implements IDatabaseDataFetcher {
    private static final Logger log = LoggerFactory.getLogger(MysqlDatabaseDataFetcherImplement.class);

    private final JdbcTemplate jdbcTemplate;
    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;
    private IDatabaseSchemaReflector schemaReflector;

    /** Spring-managed constructor. */
    @Autowired
    public MysqlDatabaseDataFetcherImplement(JdbcTemplate jdbcTemplate,
                                              ServiceLookup serviceLookup,
                                              AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
    }

    /** Test-friendly constructor — injects the reflector directly. */
    public MysqlDatabaseDataFetcherImplement(JdbcTemplate jdbcTemplate,
                                              IDatabaseSchemaReflector schemaReflector) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaReflector = schemaReflector;
        this.serviceLookup = null;
        this.appConfig = null;
    }

    private IDatabaseSchemaReflector getReflector() {
        if (schemaReflector != null) return schemaReflector;
        schemaReflector = serviceLookup.forBean(IDatabaseSchemaReflector.class,
                appConfig.getDatabaseType().getName());
        return schemaReflector;
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> buildTableDataFetcher(String tableName) {
        return env -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM `").append(tableName).append("`");
            List<Object> params = new ArrayList<>();

            applyWhere(sql, params, env);
            applyOrderBy(sql, env);
            applyLimit(sql, params, env);

            log.debug("MySQL list query: {}", sql);
            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildConnectionDataFetcher(String tableName) {
        return env -> {
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");

            int limit = first != null ? first : 20;
            long offset = after != null ? decodeCursor(after) : 0L;

            // Fetch limit+1 to detect hasNextPage
            String sql = "SELECT * FROM `" + tableName + "` LIMIT ? OFFSET ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, limit + 1, offset);

            boolean hasNextPage = rows.size() > limit;
            List<Map<String, Object>> pageRows = hasNextPage ? rows.subList(0, limit) : rows;

            List<Map<String, Object>> edges = new ArrayList<>();
            for (int i = 0; i < pageRows.size(); i++) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("node", pageRows.get(i));
                edge.put("cursor", encodeCursor(offset + i));
                edges.add(edge);
            }

            Map<String, Object> pageInfo = new HashMap<>();
            pageInfo.put("hasNextPage", hasNextPage);
            pageInfo.put("hasPreviousPage", offset > 0);
            pageInfo.put("startCursor", pageRows.isEmpty() ? null : encodeCursor(offset));
            pageInfo.put("endCursor", pageRows.isEmpty() ? null : encodeCursor(offset + pageRows.size() - 1));

            Long totalCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + tableName + "`", Long.class);

            Map<String, Object> connection = new HashMap<>();
            connection.put("edges", edges);
            connection.put("pageInfo", pageInfo);
            connection.put("totalCount", totalCount);
            return connection;
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildRelationshipDataFetcher(
            String tableName, String foreignKeyColumn,
            String referencedTable, String referencedColumn) {
        return env -> {
            Map<String, Object> source = env.getSource();
            if (source == null) return null;
            Object fkValue = source.get(foreignKeyColumn);
            if (fkValue == null) return null;

            String sql = "SELECT * FROM `" + referencedTable + "` WHERE `" + referencedColumn + "` = ? LIMIT 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, fkValue);
            return rows.isEmpty() ? null : rows.get(0);
        };
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> buildReverseRelationshipDataFetcher(
            String sourceTableName, String targetTableName,
            String foreignKeyColumn, String referencedColumn) {
        return env -> {
            Map<String, Object> source = env.getSource();
            if (source == null) return List.of();
            Object refValue = source.get(referencedColumn);
            if (refValue == null) return List.of();

            String sql = "SELECT * FROM `" + targetTableName + "` WHERE `" + foreignKeyColumn + "` = ?";
            return jdbcTemplate.queryForList(sql, refValue);
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildAggregateDataFetcher(String tableName) {
        return env -> {
            Map<String, TableInfo> tables = getReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt");
            if (tableInfo != null) {
                tableInfo.getColumns().stream()
                        .filter(col -> isNumeric(col.getType()))
                        .findFirst()
                        .ifPresent(col -> {
                            String q = "`" + col.getName() + "`";
                            sql.append(", SUM(").append(q).append(") AS sum_val");
                            sql.append(", AVG(").append(q).append(") AS avg_val");
                            sql.append(", MIN(").append(q).append(") AS min_val");
                            sql.append(", MAX(").append(q).append(") AS max_val");
                        });
            }
            sql.append(" FROM `").append(tableName).append("`");

            List<Object> params = new ArrayList<>();
            applyWhere(sql, params, env);

            Map<String, Object> raw = jdbcTemplate.queryForMap(sql.toString(), params.toArray());

            Map<String, Object> result = new HashMap<>();
            result.put("count", raw.get("cnt"));
            result.put("sum", raw.get("sum_val"));
            result.put("avg", raw.get("avg_val"));
            result.put("min", raw.get("min_val"));
            result.put("max", raw.get("max_val"));
            return result;
        };
    }

    @Override
    public DataFetcher<Object> buildComputedFieldDataFetcher(String tableName, String functionName, String fieldName) {
        return env -> null;
    }

    // ─── SQL helpers ──────────────────────────────────────────────────────────

    private void applyWhere(StringBuilder sql, List<Object> params, DataFetchingEnvironment env) {
        Object whereArg = env.getArgument("where");
        if (whereArg instanceof Map<?, ?> whereMap && !whereMap.isEmpty()) {
            sql.append(" WHERE");
            boolean first = true;
            for (Map.Entry<?, ?> entry : whereMap.entrySet()) {
                if (!first) sql.append(" AND");
                first = false;
                String col = (String) entry.getKey();
                Object condObj = entry.getValue();
                if (condObj instanceof Map<?, ?> cond) {
                    Object eq = cond.get("eq");
                    if (eq != null) {
                        sql.append(" `").append(col).append("` = ?");
                        params.add(eq);
                    }
                } else {
                    sql.append(" `").append(col).append("` = ?");
                    params.add(condObj);
                }
            }
        }
    }

    private void applyOrderBy(StringBuilder sql, DataFetchingEnvironment env) {
        Object orderByArg = env.getArgument("orderBy");
        if (orderByArg instanceof Map<?, ?> orderByMap && !orderByMap.isEmpty()) {
            sql.append(" ORDER BY");
            boolean first = true;
            for (Map.Entry<?, ?> entry : orderByMap.entrySet()) {
                if (!first) sql.append(",");
                first = false;
                String direction = "DESC".equalsIgnoreCase(String.valueOf(entry.getValue())) ? "DESC" : "ASC";
                sql.append(" `").append(entry.getKey()).append("` ").append(direction);
            }
        }
    }

    private void applyLimit(StringBuilder sql, List<Object> params, DataFetchingEnvironment env) {
        Integer limit = env.getArgument("limit");
        Integer offset = env.getArgument("offset");
        if (limit != null) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        if (offset != null) {
            sql.append(" OFFSET ?");
            params.add(offset);
        }
    }

    private boolean isNumeric(String type) {
        String t = type.toLowerCase();
        return t.contains("int") || t.contains("decimal") || t.contains("float")
                || t.contains("double") || t.contains("numeric") || t.contains("real");
    }

    private String encodeCursor(long position) {
        return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes());
    }

    private long decodeCursor(String cursor) {
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            return Long.parseLong(decoded.substring("cursor:".length()));
        } catch (Exception e) {
            return 0L;
        }
    }
}
