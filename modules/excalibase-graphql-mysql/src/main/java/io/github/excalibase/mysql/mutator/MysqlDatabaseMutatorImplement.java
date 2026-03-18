package io.github.excalibase.mysql.mutator;

import graphql.schema.DataFetcher;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataMutationException;
import io.github.excalibase.exception.NotFoundException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.StoredProcedureInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.mutator.IDatabaseMutator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL implementation of {@link IDatabaseMutator}.
 *
 * <p>Uses backtick-quoted identifiers and {@link GeneratedKeyHolder} to retrieve
 * auto-increment primary keys after INSERT. The schema reflector is resolved lazily
 * via {@link ServiceLookup}, mirroring the Postgres pattern.</p>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlDatabaseMutatorImplement implements IDatabaseMutator {
    private static final Logger log = LoggerFactory.getLogger(MysqlDatabaseMutatorImplement.class);

    private final JdbcTemplate jdbcTemplate;
    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;
    private IDatabaseSchemaReflector schemaReflector;

    /** Spring-managed constructor. */
    @Autowired
    public MysqlDatabaseMutatorImplement(JdbcTemplate jdbcTemplate,
                                          ServiceLookup serviceLookup,
                                          AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
    }

    /** Test-friendly constructor — injects the reflector directly. */
    public MysqlDatabaseMutatorImplement(JdbcTemplate jdbcTemplate,
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
    public DataFetcher<Map<String, Object>> buildCreateMutationResolver(String tableName) {
        return env -> {
            Map<String, Object> input = env.getArgument("input");
            if (input == null || input.isEmpty()) {
                throw new DataMutationException("No input provided for create" + tableName);
            }
            String pkColumn = getPkColumn(getTable(tableName));
            return insertAndFetch(tableName, pkColumn, input);
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildUpdateMutationResolver(String tableName) {
        return env -> {
            Object idArg = env.getArgument("id");
            Map<String, Object> input = env.getArgument("input");
            if (idArg == null) throw new DataMutationException("id is required for update");
            if (input == null || input.isEmpty()) {
                throw new DataMutationException("No input provided for update " + tableName);
            }

            String pkColumn = getPkColumn(getTable(tableName));

            List<String> setCols = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            for (Map.Entry<String, Object> e : input.entrySet()) {
                setCols.add("`" + e.getKey() + "` = ?");
                vals.add(e.getValue());
            }
            vals.add(idArg);

            String sql = "UPDATE `" + tableName + "` SET " + String.join(", ", setCols)
                    + " WHERE `" + pkColumn + "` = ?";

            int updated = jdbcTemplate.update(sql, vals.toArray());
            if (updated == 0) {
                throw new NotFoundException("Record not found in " + tableName + " with id=" + idArg);
            }

            return fetchById(tableName, pkColumn, ((Number) idArg).longValue());
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildDeleteMutationResolver(String tableName) {
        return env -> {
            Object idArg = env.getArgument("id");
            if (idArg == null) throw new DataMutationException("id is required for delete");

            String pkColumn = getPkColumn(getTable(tableName));
            long id = ((Number) idArg).longValue();

            Map<String, Object> existing = fetchById(tableName, pkColumn, id);
            if (existing == null) {
                throw new NotFoundException("Record not found in " + tableName + " with id=" + id);
            }

            jdbcTemplate.update("DELETE FROM `" + tableName + "` WHERE `" + pkColumn + "` = ?", id);
            return existing;
        };
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> buildBulkCreateMutationResolver(String tableName) {
        return env -> {
            List<Map<String, Object>> inputList = env.getArgument("inputs");
            if (inputList == null || inputList.isEmpty()) return List.of();

            String pkColumn = getPkColumn(getTable(tableName));
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> input : inputList) {
                results.add(insertAndFetch(tableName, pkColumn, input));
            }
            return results;
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildCreateWithRelationshipsMutationResolver(String tableName) {
        return buildCreateMutationResolver(tableName);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private TableInfo getTable(String tableName) {
        TableInfo t = getReflector().reflectSchema().get(tableName);
        if (t == null) throw new DataMutationException("Table not found: " + tableName);
        return t;
    }

    private String getPkColumn(TableInfo tableInfo) {
        return tableInfo.getColumns().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .map(ColumnInfo::getName)
                .findFirst()
                .orElse("id");
    }

    private Map<String, Object> insertAndFetch(String tableName, String pkColumn,
                                                Map<String, Object> input) {
        List<String> cols = new ArrayList<>(input.keySet());
        List<Object> vals = new ArrayList<>(input.values());

        String colsSql = String.join(", ", cols.stream().map(c -> "`" + c + "`").toList());
        String valsSql = String.join(", ", cols.stream().map(c -> "?").toList());
        String sql = "INSERT INTO `" + tableName + "` (" + colsSql + ") VALUES (" + valsSql + ")";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < vals.size(); i++) {
                Object val = vals.get(i);
                // MySQL JDBC doesn't natively handle JsonNode — serialize to JSON string
                if (val instanceof JsonNode) {
                    ps.setObject(i + 1, val.toString());
                } else {
                    ps.setObject(i + 1, val);
                }
            }
            return ps;
        }, keyHolder);

        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new DataMutationException("Failed to retrieve generated key for " + tableName);
        }

        return fetchById(tableName, pkColumn, generatedKey.longValue());
    }

    private Map<String, Object> fetchById(String tableName, String pkColumn, long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM `" + tableName + "` WHERE `" + pkColumn + "` = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public DataFetcher<Object> buildProcedureMutationResolver(StoredProcedureInfo procedure) {
        return env -> {
            List<StoredProcedureInfo.ProcedureParam> params = procedure.getParameters();
            List<StoredProcedureInfo.ProcedureParam> outParams = params.stream()
                    .filter(StoredProcedureInfo.ProcedureParam::isOut)
                    .toList();

            // Build CALL statement: CALL proc(?, ?, ...)
            String placeholders = params.isEmpty() ? "" :
                    "?,".repeat(params.size()).substring(0, params.size() * 2 - 1);
            String sql = "CALL `" + procedure.getName() + "`(" + placeholders + ")";

            return jdbcTemplate.execute((java.sql.Connection con) -> {
                try (CallableStatement cs = con.prepareCall(sql)) {
                    // Bind IN params and register OUT params
                    for (StoredProcedureInfo.ProcedureParam p : params) {
                        int idx = p.getPosition();
                        if (p.isIn()) {
                            Object val = env.getArguments().get(p.getName());
                            cs.setObject(idx, val);
                        }
                        if (p.isOut()) {
                            cs.registerOutParameter(idx, sqlTypeFor(p.getDataType()));
                        }
                    }
                    cs.execute();

                    if (outParams.isEmpty()) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("success", true);
                        return result;
                    }

                    Map<String, Object> result = new HashMap<>();
                    for (StoredProcedureInfo.ProcedureParam p : outParams) {
                        result.put(p.getName(), cs.getObject(p.getPosition()));
                    }
                    return result;
                }
            });
        };
    }

    private int sqlTypeFor(String dataType) {
        if (dataType == null) return Types.OTHER;
        return switch (dataType.toLowerCase()) {
            case "int", "integer", "tinyint", "smallint", "mediumint" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "decimal", "numeric" -> Types.DECIMAL;
            case "float" -> Types.FLOAT;
            case "double" -> Types.DOUBLE;
            case "varchar", "char", "text", "tinytext", "mediumtext", "longtext" -> Types.VARCHAR;
            case "date" -> Types.DATE;
            case "datetime", "timestamp" -> Types.TIMESTAMP;
            case "boolean", "bool" -> Types.BOOLEAN;
            default -> Types.OTHER;
        };
    }
}
