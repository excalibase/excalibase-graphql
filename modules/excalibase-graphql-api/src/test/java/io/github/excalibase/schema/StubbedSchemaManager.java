package io.github.excalibase.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.spi.SqlEngineFactory;

/**
 * The real schema manager over a fixed schema and one permission document, with no database: for
 * tests of the surfaces that take their plans from it.
 */
public final class StubbedSchemaManager extends GraphqlSchemaManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaInfo schema;
    private final ProbeRunner probes;

    private StubbedSchemaManager(SchemaInfo schema, PermissionProvider permissions, ProbeRunner probes) {
        super(null, null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, permissions, true);
        this.schema = schema;
        this.probes = probes;
    }

    /** @param document a whole permission document, as the control plane serves it */
    public static StubbedSchemaManager withDocument(SchemaInfo schema, String document, ProbeRunner probes) {
        PermissionSet permissions = parse(document);
        return new StubbedSchemaManager(schema, new PermissionProvider() {
            @Override
            public PermissionSet permissionsFor(String projectId) {
                return permissions;
            }

            @Override
            public void evict(String projectId) {
                // nothing cached
            }
        }, probes);
    }

    /** Reads its permissions from {@code permissions}, however that answers. */
    public static StubbedSchemaManager withProvider(SchemaInfo schema, PermissionProvider permissions) {
        return new StubbedSchemaManager(schema, permissions, (sql, params) -> false);
    }

    /** A document with the given {@code tables} entries and no functions. */
    public static String document(String projectId, String... tableEntries) {
        return "{\"projectId\":\"" + projectId + "\",\"version\":1,\"tables\":[" + String.join(",", tableEntries)
                + "],\"functions\":[],\"functionPermissions\":[]}";
    }

    @Override
    Reflection reflect(String orgSlug, String projectId) {
        return new Reflection(schema, "public", SqlEngineFactory.create("postgres"), null, probes);
    }

    private static PermissionSet parse(String document) {
        try {
            return PermissionSetParser.parse(MAPPER.readTree(document));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
