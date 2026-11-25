package io.github.excalibase.mongodb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A collection as declared in configuration.
 *
 * @param idKind the type of {@code _id}; the GraphQL field is {@code id}
 * @param fields every other readable field, in declaration order
 */
public record CollectionDef(String name, FieldKind idKind, Map<String, FieldKind> fields) {

    public static final String ID_FIELD = "id";
    public static final String MONGO_ID = "_id";

    public CollectionDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(idKind, "idKind");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** The kind of a GraphQL-side field name, {@code id} included. */
    public FieldKind kindOf(String field) {
        return ID_FIELD.equals(field) ? idKind : fields.get(field);
    }

    public boolean declares(String field) {
        return ID_FIELD.equals(field) || fields.containsKey(field);
    }

    /** The document key a GraphQL-side field is stored under. */
    public static String storedName(String field) {
        return ID_FIELD.equals(field) ? MONGO_ID : field;
    }
}
