package io.github.excalibase.mysql.fetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final String BATCH_CONTEXT = "BATCH_CONTEXT";
    private static final String REVERSE_BATCH_PREFIX = "REV:";

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
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            DataFetchingFieldSelectionSet selectionSet = env.getSelectionSet();
            if (!results.isEmpty() && selectionSet != null) {
                preloadRelationships(env, tableName, results, selectionSet);
            }
            return results;
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
            StringBuilder sql = new StringBuilder("SELECT * FROM `").append(tableName).append("`");
            List<Object> params = new ArrayList<>();
            applyWhere(sql, params, env);
            applyOrderBy(sql, env);
            sql.append(" LIMIT ?");
            params.add(limit + 1);
            sql.append(" OFFSET ?");
            params.add(offset);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

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

            StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM `").append(tableName).append("`");
            List<Object> countParams = new ArrayList<>();
            applyWhere(countSql, countParams, env);
            Long totalCount = jdbcTemplate.queryForObject(countSql.toString(), Long.class, countParams.toArray());

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

            // Check pre-loaded batch context first
            Map<String, Object> batchContext = env.getGraphQlContext().get(BATCH_CONTEXT);
            if (batchContext != null && batchContext.containsKey(referencedTable)) {
                @SuppressWarnings("unchecked")
                Map<Object, Map<String, Object>> grouped =
                        (Map<Object, Map<String, Object>>) batchContext.get(referencedTable);
                return grouped.get(fkValue);
            }

            String sql = "SELECT * FROM `" + referencedTable + "` WHERE `" + referencedColumn + "` = ? LIMIT 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, fkValue);
            return rows.isEmpty() ? null : rows.getFirst();
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildRelationshipDataFetcher(
            String tableName, List<String> foreignKeyColumns,
            String referencedTable, List<String> referencedColumns) {
        return env -> {
            Map<String, Object> source = env.getSource();
            if (source == null) return null;
            StringBuilder sql = new StringBuilder("SELECT * FROM `" + referencedTable + "` WHERE ");
            List<Object> params = new ArrayList<>();
            for (int i = 0; i < foreignKeyColumns.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append("`").append(referencedColumns.get(i)).append("` = ?");
                params.add(source.get(foreignKeyColumns.get(i)));
            }
            sql.append(" LIMIT 1");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            return rows.isEmpty() ? null : rows.getFirst();
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

            // Check pre-loaded batch context first
            String cacheKey = REVERSE_BATCH_PREFIX + targetTableName + ":" + foreignKeyColumn;
            Map<String, Object> batchContext = env.getGraphQlContext().get(BATCH_CONTEXT);
            if (batchContext != null && batchContext.containsKey(cacheKey)) {
                @SuppressWarnings("unchecked")
                Map<Object, List<Map<String, Object>>> grouped =
                        (Map<Object, List<Map<String, Object>>>) batchContext.get(cacheKey);
                List<Map<String, Object>> cached = grouped.get(refValue);
                return cached != null ? cached : List.of();
            }

            String sql = "SELECT * FROM `" + targetTableName + "` WHERE `" + foreignKeyColumn + "` = ?";
            return jdbcTemplate.queryForList(sql, refValue);
        };
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> buildReverseRelationshipDataFetcher(
            String sourceTableName, String targetTableName,
            List<String> foreignKeyColumns, List<String> referencedColumns) {
        if (foreignKeyColumns.size() == 1) {
            return buildReverseRelationshipDataFetcher(sourceTableName, targetTableName,
                    foreignKeyColumns.getFirst(), referencedColumns.getFirst());
        }
        return env -> {
            Map<String, Object> source = env.getSource();
            if (source == null) return List.of();
            StringBuilder sql = new StringBuilder("SELECT * FROM `" + targetTableName + "` WHERE ");
            List<Object> params = new ArrayList<>();
            for (int i = 0; i < foreignKeyColumns.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append("`").append(foreignKeyColumns.get(i)).append("` = ?");
                params.add(source.get(referencedColumns.get(i)));
            }
            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildAggregateDataFetcher(String tableName) {
        return env -> {
            Map<String, TableInfo> tables = getReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt");
            if (tableInfo != null) {
                // Prefer decimal/float columns first — more meaningful than integer PKs.
                // Fall back to non-PK integer columns if no decimal column exists.
                Optional<ColumnInfo> numericCol = tableInfo.getColumns().stream()
                        .filter(col -> isDecimalOrFloat(col.getType()))
                        .findFirst();
                if (numericCol.isEmpty()) {
                    numericCol = tableInfo.getColumns().stream()
                            .filter(col -> isNumeric(col.getType()) && !col.isPrimaryKey())
                            .findFirst();
                }
                numericCol.ifPresent(col -> {
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

    @SuppressWarnings("unchecked")
    private void applyWhere(StringBuilder sql, List<Object> params, DataFetchingEnvironment env) {
        Object whereArg = env.getArgument("where");
        if (!(whereArg instanceof Map<?, ?> whereMap) || whereMap.isEmpty()) return;

        String clause = buildWhereClause((Map<String, Object>) whereMap, params);
        if (!clause.isEmpty()) {
            sql.append(" WHERE ").append(clause);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildWhereClause(Map<String, Object> whereMap, List<Object> params) {
        // Handle top-level `or` key: OR of multiple condition maps
        Object orArg = whereMap.get("or");
        if (orArg instanceof List<?> orList && !orList.isEmpty()) {
            List<String> orClauses = new ArrayList<>();
            for (Object item : orList) {
                if (item instanceof Map<?, ?> orBranch) {
                    String branch = buildWhereClause((Map<String, Object>) orBranch, params);
                    if (!branch.isEmpty()) orClauses.add("(" + branch + ")");
                }
            }
            if (!orClauses.isEmpty()) return String.join(" OR ", orClauses);
            return "";
        }

        List<String> clauses = new ArrayList<>();
        for (Map.Entry<?, ?> entry : whereMap.entrySet()) {
            if ("or".equals(entry.getKey())) continue; // already handled above
            String col = "`" + entry.getKey() + "`";
            Object condObj = entry.getValue();

            if (condObj instanceof Map<?, ?> cond) {
                buildColumnClauses(col, (Map<String, Object>) cond, clauses, params);
            } else if (condObj != null) {
                clauses.add(col + " = ?");
                params.add(condObj);
            }
        }

        return String.join(" AND ", clauses);
    }

    private void buildColumnClauses(String col, Map<String, Object> cond,
                                     List<String> clauses, List<Object> params) {
        Object eq       = cond.get("eq");
        Object neq      = cond.get("neq");
        Object gt       = cond.get("gt");
        Object gte      = cond.get("gte");
        Object lt       = cond.get("lt");
        Object lte      = cond.get("lte");
        Object contains = cond.get("contains");
        Object startsWith = cond.get("startsWith");
        Object endsWith   = cond.get("endsWith");
        Object like     = cond.get("like");
        Object isNull   = cond.get("isNull");
        Object isNotNull = cond.get("isNotNull");
        Object in       = cond.get("in");
        Object notIn    = cond.get("notIn");

        if (eq != null)         { clauses.add(col + " = ?");    params.add(eq); }
        if (neq != null)        { clauses.add(col + " != ?");   params.add(neq); }
        if (gt != null)         { clauses.add(col + " > ?");    params.add(gt); }
        if (gte != null)        { clauses.add(col + " >= ?");   params.add(gte); }
        if (lt != null)         { clauses.add(col + " < ?");    params.add(lt); }
        if (lte != null)        { clauses.add(col + " <= ?");   params.add(lte); }
        if (contains != null)   { clauses.add(col + " LIKE ?"); params.add("%" + contains + "%"); }
        if (startsWith != null) { clauses.add(col + " LIKE ?"); params.add(startsWith + "%"); }
        if (endsWith != null)   { clauses.add(col + " LIKE ?"); params.add("%" + endsWith); }
        if (like != null)       { clauses.add(col + " LIKE ?"); params.add(like); }

        if (Boolean.TRUE.equals(isNull))    clauses.add(col + " IS NULL");
        if (Boolean.TRUE.equals(isNotNull)) clauses.add(col + " IS NOT NULL");

        if (in instanceof List<?> inList && !inList.isEmpty()) {
            String placeholders = String.join(", ", inList.stream().map(x -> "?").toList());
            clauses.add(col + " IN (" + placeholders + ")");
            params.addAll(inList);
        }
        if (notIn instanceof List<?> notInList && !notInList.isEmpty()) {
            String placeholders = String.join(", ", notInList.stream().map(x -> "?").toList());
            clauses.add(col + " NOT IN (" + placeholders + ")");
            params.addAll(notInList);
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

    private boolean isDecimalOrFloat(String type) {
        String t = type.toLowerCase();
        return t.contains("decimal") || t.contains("float") || t.contains("double")
                || t.contains("numeric") || t.contains("real");
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

    // ─── Relationship preloading (N+1 prevention) ─────────────────────────────

    private void preloadRelationships(DataFetchingEnvironment env, String tableName,
                                      List<Map<String, Object>> results,
                                      DataFetchingFieldSelectionSet selectionSet) {
        Map<String, TableInfo> tables = getReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        if (tableInfo == null) return;

        Set<String> columnNames = tableInfo.getColumns().stream()
                .map(ColumnInfo::getName)
                .collect(Collectors.toSet());

        Set<String> relationshipFields = selectionSet.getFields().stream()
                .map(SelectedField::getName)
                .filter(name -> !columnNames.contains(name))
                .collect(Collectors.toSet());

        if (relationshipFields.isEmpty()) return;

        Map<String, Object> batchContext = env.getGraphQlContext().get(BATCH_CONTEXT);
        if (batchContext == null) {
            batchContext = new HashMap<>();
            env.getGraphQlContext().put(BATCH_CONTEXT, batchContext);
        }

        for (String relField : relationshipFields) {
            Optional<ForeignKeyInfo> fkInfo = tableInfo.getForeignKeys().stream()
                    .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(relField) && !fk.isComposite())
                    .findFirst();

            if (fkInfo.isPresent()) {
                preloadForwardFk(fkInfo.get(), results, batchContext);
            } else {
                preloadReverseIfMatches(relField, tableName, tables, results, batchContext);
            }
        }
    }

    private void preloadForwardFk(ForeignKeyInfo fk, List<Map<String, Object>> results,
                                   Map<String, Object> batchContext) {
        String fkColumn = fk.getColumnName();
        String referencedTable = fk.getReferencedTable();
        String referencedColumn = fk.getReferencedColumn();

        if (batchContext.containsKey(referencedTable)) return;

        Set<Object> fkValues = results.stream()
                .map(row -> row.get(fkColumn))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (fkValues.isEmpty()) return;

        String placeholders = fkValues.stream().map(v -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT * FROM `" + referencedTable + "` WHERE `" + referencedColumn + "` IN (" + placeholders + ")";

        List<Map<String, Object>> relatedRecords = jdbcTemplate.queryForList(sql, fkValues.toArray());

        Map<Object, Map<String, Object>> grouped = new HashMap<>();
        for (Map<String, Object> record : relatedRecords) {
            Object keyValue = record.get(referencedColumn);
            if (keyValue != null) {
                grouped.put(keyValue, record);
            }
        }
        batchContext.put(referencedTable, grouped);
        log.debug("Preloaded forward FK {}: {} values → {} records", referencedTable, fkValues.size(), relatedRecords.size());
    }

    private void preloadReverseIfMatches(String relField, String parentTableName,
                                          Map<String, TableInfo> tables,
                                          List<Map<String, Object>> parentResults,
                                          Map<String, Object> batchContext) {
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String otherTableName = entry.getKey();
            if (otherTableName.equals(parentTableName)) continue;

            for (ForeignKeyInfo otherFk : entry.getValue().getForeignKeys()) {
                if (!otherFk.getReferencedTable().equalsIgnoreCase(parentTableName)) continue;
                if (otherFk.isComposite()) continue;

                String computedField = toLowerCamelCase(otherTableName);
                if (!computedField.endsWith("s")) computedField += "s";
                if (!computedField.equalsIgnoreCase(relField)) continue;

                String fkColumn = otherFk.getColumnName();
                String referencedCol = otherFk.getReferencedColumn();
                String cacheKey = REVERSE_BATCH_PREFIX + otherTableName + ":" + fkColumn;

                if (batchContext.containsKey(cacheKey)) return;

                Set<Object> parentKeys = parentResults.stream()
                        .map(row -> row.get(referencedCol))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (parentKeys.isEmpty()) return;

                String placeholders = parentKeys.stream().map(v -> "?").collect(Collectors.joining(", "));
                String sql = "SELECT * FROM `" + otherTableName + "` WHERE `" + fkColumn + "` IN (" + placeholders + ")";

                List<Map<String, Object>> relatedRecords = jdbcTemplate.queryForList(sql, parentKeys.toArray());

                Map<Object, List<Map<String, Object>>> grouped = new HashMap<>();
                for (Map<String, Object> record : relatedRecords) {
                    Object keyValue = record.get(fkColumn);
                    if (keyValue != null) {
                        grouped.computeIfAbsent(keyValue, k -> new ArrayList<>()).add(record);
                    }
                }
                batchContext.put(cacheKey, grouped);
                log.debug("Preloaded reverse FK {}: {} parent keys → {} child records",
                        cacheKey, parentKeys.size(), relatedRecords.size());
                return;
            }
        }
    }

    private static String toLowerCamelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        boolean first = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (first) {
                sb.append(Character.toLowerCase(c));
                first = false;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
