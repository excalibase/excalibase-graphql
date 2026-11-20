package io.github.excalibase.mongodb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** The collections the engine serves: exactly the ones declared, nothing is discovered. */
public final class Catalog {

    private static final Pattern GRAPHQL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private static final Set<FieldKind> ID_KINDS =
            EnumSet.of(FieldKind.OBJECT_ID, FieldKind.STRING, FieldKind.INT, FieldKind.LONG);

    private final Map<String, CollectionDef> collections;

    private Catalog(Map<String, CollectionDef> collections) {
        this.collections = Map.copyOf(collections);
    }

    public static Catalog of(CollectionDef... defs) {
        Map<String, CollectionDef> byName = new LinkedHashMap<>();
        for (CollectionDef def : defs) {
            byName.put(def.name(), def);
        }
        return new Catalog(byName);
    }

    @SuppressWarnings("unchecked")
    public static Catalog fromSettings(Map<String, Object> settings) {
        if (!(settings.get("collections") instanceof List<?> declared) || declared.isEmpty()) {
            throw new IllegalStateException("No collections declared: set \"collections\" with each collection's fields");
        }
        Map<String, CollectionDef> byName = new LinkedHashMap<>();
        for (Object entry : declared) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("A collection must be an object with name, id and fields");
            }
            CollectionDef def = parse((Map<String, Object>) raw);
            if (byName.put(def.name(), def) != null) {
                throw new IllegalStateException("Collection \"" + def.name() + "\" is declared twice");
            }
        }
        return new Catalog(byName);
    }

    private static CollectionDef parse(Map<String, Object> raw) {
        String name = String.valueOf(raw.get("name"));
        requireName("collection", name);
        FieldKind idKind = raw.get("id") == null ? FieldKind.OBJECT_ID : kind(name, "id", raw.get("id"));
        if (!ID_KINDS.contains(idKind)) {
            throw new IllegalStateException(name + ".id: an id is an objectId, string, int or long, not " + idKind);
        }
        Map<String, FieldKind> fields = new LinkedHashMap<>();
        if (raw.get("fields") instanceof Map<?, ?> declared) {
            declared.forEach((field, type) -> {
                String fieldName = String.valueOf(field);
                requireName("field of " + name, fieldName);
                if (CollectionDef.ID_FIELD.equals(fieldName)) {
                    throw new IllegalStateException("Collection " + name + " declares a field \"id\", which is the id: "
                            + "declare the id type with \"id\" instead");
                }
                fields.put(fieldName, kind(name, fieldName, type));
            });
        }
        return new CollectionDef(name, idKind, fields);
    }

    private static FieldKind kind(String collection, String field, Object declared) {
        try {
            return FieldKind.parse(String.valueOf(declared));
        } catch (IllegalStateException e) {
            throw new IllegalStateException(collection + "." + field + ": " + e.getMessage(), e);
        }
    }

    private static void requireName(String what, String name) {
        if (!GRAPHQL_NAME.matcher(name).matches()) {
            throw new IllegalStateException("The " + what + " name \"" + name
                    + "\" is not usable: letters, digits and underscore only, starting with a letter");
        }
    }

    public Optional<CollectionDef> collection(String name) {
        return Optional.ofNullable(collections.get(name));
    }

    public Collection<CollectionDef> all() {
        return collections.values();
    }
}
