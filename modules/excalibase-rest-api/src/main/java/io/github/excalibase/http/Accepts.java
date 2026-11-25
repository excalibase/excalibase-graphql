package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;

import java.util.Collection;

/** Whether a request's {@code Accept} header admits a media type, wildcards and structured-syntax suffixes included. */
public final class Accepts {

    private static final String WILDCARD = "*";

    private Accepts() {
    }

    /** True when the request names no preference, or names a type that covers {@code type/subtype}. */
    public static boolean includes(HttpRequest<?> request, String type, String subtype) {
        Collection<MediaType> accepted = request.accept();
        return accepted.isEmpty() || accepted.stream().anyMatch(candidate -> covers(candidate, type, subtype));
    }

    private static boolean covers(MediaType accepted, String type, String subtype) {
        if (WILDCARD.equals(accepted.getType())) {
            return true;
        }
        return type.equalsIgnoreCase(accepted.getType()) && coversSubtype(accepted.getSubtype(), subtype);
    }

    /** {@code *} covers any subtype; {@code *+json} any subtype with that suffix. */
    private static boolean coversSubtype(String accepted, String subtype) {
        if (WILDCARD.equals(accepted) || accepted.equalsIgnoreCase(subtype)) {
            return true;
        }
        return accepted.startsWith("*+") && subtype.toLowerCase().endsWith(accepted.substring(1).toLowerCase());
    }
}
