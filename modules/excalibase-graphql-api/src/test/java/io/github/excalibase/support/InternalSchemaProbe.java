package io.github.excalibase.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** What the never-expose tests look for in an answer: any word that only an internal schema could have put there. */
public final class InternalSchemaProbe {

    /** Every type with its fields: Query, Mutation and Subscription are among them. */
    public static final String INTROSPECTION = "{ __schema { types { name fields { name } } } }";

    private static final Set<String> INTERNAL_WORDS = Set.of("excalibase_meta", "excalibasemeta", "auth_users",
            "authusers", "read_t", "readt", "read_users", "readusers", "leak_meta", "leakmeta", "meta_label",
            "metalabel", "document-secret", "hash-secret", "password_hash", "passwordhash");

    private InternalSchemaProbe() {
    }

    public static void assertNothingInternal(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain(INTERNAL_WORDS);
    }

    /** The field names of every type that has {@code field}, from an {@link #INTROSPECTION} answer. */
    public static Set<String> fieldsOfTypeWith(JsonNode introspection, String field) {
        Set<String> names = new TreeSet<>();
        for (JsonNode type : introspection.at("/data/__schema/types")) {
            Set<String> own = new TreeSet<>();
            type.path("fields").forEach(node -> own.add(node.get("name").asText()));
            if (own.contains(field)) {
                names.addAll(own);
            }
        }
        return names;
    }
}
