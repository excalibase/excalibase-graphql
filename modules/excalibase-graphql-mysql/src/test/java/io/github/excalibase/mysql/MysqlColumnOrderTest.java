package io.github.excalibase.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Columns and primary key parts land in the table's declared order, whatever order the query's rows arrive in. */
class MysqlColumnOrderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DECLARED = List.of("zulu", "id", "alpha_long_name", "b", "mid");

    private static JsonNode row(String kind, String column, int position) throws Exception {
        return JSON.readTree("{\"kind\":\"" + kind + "\",\"table_schema\":\"app\",\"table_name\":\"t\","
                + "\"column_name\":\"" + column + "\",\"data_type\":\"int\",\"column_type\":\"int\","
                + "\"position\":" + position + "}");
    }

    @Test
    void shuffledRows_areAppliedInDeclaredOrder() throws Exception {
        for (long seed = 0; seed < 20; seed++) {
            MysqlSchemaLoader.Introspected introspected = new MysqlSchemaLoader.Introspected();
            List<JsonNode> columns = new ArrayList<>();
            for (int i = 0; i < DECLARED.size(); i++) {
                columns.add(row("column", DECLARED.get(i), i + 1));
            }
            Collections.shuffle(columns, new Random(seed));
            introspected.columns.addAll(columns);
            introspected.primaryKeyParts.add(row("pk", "b", 2));
            introspected.primaryKeyParts.add(row("pk", "zulu", 1));
            Map<String, SchemaInfo> perSchema = new HashMap<>();
            perSchema.put("app", new SchemaInfo());

            introspected.apply(perSchema, new MysqlSchemaLoader());

            assertThat(new ArrayList<>(perSchema.get("app").getColumns("t"))).as("seed " + seed).isEqualTo(DECLARED);
            assertThat(perSchema.get("app").getPrimaryKeys("t")).as("seed " + seed).containsExactly("zulu", "b");
        }
    }
}
