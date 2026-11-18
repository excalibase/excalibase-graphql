package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a posted document with the parser the engine loads with, the same reserved schemas and project, and the same
 * rule about a {@code jwt:} section beside the engine's own token settings. It only reads: nothing is fetched,
 * swapped in or written, so what the engine serves is the same before and after.
 */
public final class DocumentValidation {

    private DocumentValidation() {
    }

    /** @param tables rules read, {@code functions} tracked functions; both 0 when the document is refused */
    public record Result(boolean valid, List<String> errors, int tables, int functions) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("valid", valid);
            if (valid) {
                map.put("tables", tables);
                map.put("functions", functions);
            } else {
                map.put("errors", errors);
            }
            return map;
        }
    }

    public static Result validate(byte[] body, ReservedSchemas reserved, String projectId, boolean envJwtConfigured) {
        if (body == null || body.length == 0) {
            return refused("the document is empty");
        }
        if (body.length > MetadataDocumentParser.MAX_BYTES) {
            return refused("the document is too large: at most " + MetadataDocumentParser.MAX_BYTES + " bytes");
        }
        String text;
        try {
            text = FileMetadataSource.decodeUtf8(body);
        } catch (IOException _) {
            return refused("the document is not UTF-8 text");
        }
        try {
            PermissionSet permissions = new MetadataDocumentParser(reserved, projectId, envJwtConfigured).parse(text, 1);
            return new Result(true, List.of(), permissions.tables().size(), permissions.functions().size());
        } catch (MetadataDocumentException e) {
            return refused(e.getMessage());
        }
    }

    private static Result refused(String error) {
        return new Result(false, List.of(error), 0, 0);
    }
}
