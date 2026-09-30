package io.github.excalibase.permissions.compile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExpParser;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;

/** The orgs / members / notes schema the compiler tests share, keyed like the merged engine schema. */
final class FixtureSchema {

    static final String ORGS = "public.orgs";
    static final String MEMBERS = "public.members";
    static final String NOTES = "public.notes";

    static final String U1 = "11111111-1111-1111-1111-111111111111";
    static final String U2 = "22222222-2222-2222-2222-222222222222";
    static final String U3 = "33333333-3333-3333-3333-333333333333";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FixtureSchema() {
    }

    static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        columns(schema, ORGS, "id", "integer", "name", "text");
        columns(schema, MEMBERS, "org_id", "integer", "user_id", "uuid", "role", "text");
        columns(schema, NOTES, "id", "integer", "owner_id", "uuid", "org_id", "integer", "title", "text",
                "amount", "numeric", "status", "text", "created_at", "timestamp with time zone",
                "priority", "integer", "pinned", "boolean", "due", "date", "views", "bigint",
                "mood", "mood", "ip", "inet", "local_at", "timestamp without time zone", "score", "double precision");
        schema.addColumnEnumType(NOTES, "mood", "public.mood");
        List.of("sad", "ok", "happy").forEach(label -> schema.addEnumValue("public.mood", label));
        foreignKeys(schema);
        return schema;
    }

    static void foreignKeys(SchemaInfo schema) {
        schema.addForeignKey(NOTES, "org_id", ORGS, "id");
        schema.addForeignKey(MEMBERS, "org_id", ORGS, "id");
        schema.addCompositeForeignKey(NOTES, List.of("org_id", "owner_id"), MEMBERS, List.of("org_id", "user_id"));
    }

    private static void columns(SchemaInfo schema, String table, String... namesAndTypes) {
        schema.setTableSchema(table, "public");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            schema.addColumn(table, namesAndTypes[i], namesAndTypes[i + 1]);
        }
    }

    static BoolExp parse(String json) {
        try {
            return BoolExpParser.parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
