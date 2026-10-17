package io.github.excalibase.permissions.compile;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;

import java.util.List;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;

/** The orgs / members / notes schema as the MySQL loader reflects it: data types plus declarations. */
public final class MysqlFixtureSchema {

    static final String BIN = "utf8mb4_bin";

    private MysqlFixtureSchema() {
    }

    public static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        columns(schema, ORGS, "id", "int", "name", "varchar(100)");
        columns(schema, MEMBERS, "org_id", "int", "user_id", "char(36)", "role", "varchar(20)");
        columns(schema, NOTES, "id", "int", "owner_id", "char(36)", "org_id", "int", "title", "varchar(100)",
                "amount", "decimal(10,2)", "status", "varchar(20)", "created_at", "timestamp(6)", "priority", "int",
                "pinned", "tinyint(1)", "due", "date", "views", "bigint", "mood", "enum('sad','ok','happy')",
                "ip", "varchar(45)", "local_at", "datetime(6)", "score", "double", "ratio", "float", "meta", "json",
                "big", "bigint unsigned", "small", "int unsigned", "level", "tinyint");
        schema.addPrimaryKey(ORGS, "id");
        schema.addPrimaryKey(MEMBERS, "org_id");
        schema.addPrimaryKey(MEMBERS, "user_id");
        schema.addPrimaryKey(NOTES, "id");
        FixtureSchema.foreignKeys(schema);
        return schema;
    }

    private static void columns(SchemaInfo schema, String table, String... namesAndTypes) {
        schema.setTableSchema(table, "public");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            String columnType = namesAndTypes[i + 1];
            String dataType = columnType.replaceAll("\\(.*", "").replace(" unsigned", "");
            boolean text = List.of("varchar", "char", "enum").contains(dataType);
            schema.addColumn(table, namesAndTypes[i], "enum".equals(dataType) ? "varchar" : dataType);
            schema.addColumnDeclaration(table, namesAndTypes[i],
                    new ColumnDeclaration(columnType, text ? "utf8mb4" : null, text ? BIN : null));
        }
    }
}
