package io.github.excalibase.mongodb;

import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.math.BigInteger;

/**
 * Turns a value into exactly the BSON scalar the declared kind stores. Anything that is not a plain scalar
 * of that kind is refused, so a value can never be a document, an operator or a path: it is only ever data.
 */
// the BSON driver reads and writes dates as java.util.Date; there is no other type to exchange them in
@SuppressWarnings("java:S2143")
final class ValueCodec {

    private ValueCodec() {}

    static Object toBson(FieldKind kind, Object value, String field) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case STRING -> text(value, field);
            case OBJECT_ID -> objectId(value, field);
            case INT -> value instanceof Integer ? value : refuse(field, "a whole number");
            case LONG -> value instanceof Integer || value instanceof Long ? ((Number) value).longValue()
                    : refuse(field, "a whole number");
            case DOUBLE -> toDouble(value, field);
            case BOOLEAN -> value instanceof Boolean ? value : refuse(field, "true or false");
            case DATETIME -> instant(value, field);
        };
    }

    /** An id as GraphQL's ID type carries it: text, parsed by the collection's declared id kind. */
    static Object parseId(FieldKind kind, String text) {
        return switch (kind) {
            case OBJECT_ID -> objectId(text, CollectionDef.ID_FIELD);
            case STRING -> text;
            case INT -> parseWhole(text, true);
            case LONG -> parseWhole(text, false);
            default -> throw new IllegalStateException("A collection cannot use " + kind + " as its id");
        };
    }

    /** What GraphQL's ID type shows for a stored id. */
    static String formatId(Object stored) {
        return stored instanceof ObjectId objectId ? objectId.toHexString() : String.valueOf(stored);
    }

    private static Object parseWhole(String text, boolean narrow) {
        try {
            return narrow ? (Object) Integer.parseInt(text) : (Object) Long.parseLong(text);
        } catch (NumberFormatException _) {
            throw new RequestException("The id is not a valid number");
        }
    }

    private static Object toDouble(Object value, String field) {
        if (value instanceof BigInteger || !(value instanceof Number number)) {
            return refuse(field, "a number");
        }
        return number.doubleValue();
    }

    private static Object text(Object value, String field) {
        return value instanceof String ? value : refuse(field, "text");
    }

    private static Object objectId(Object value, String field) {
        if (value instanceof ObjectId) {
            return value;
        }
        if (value instanceof String text && ObjectId.isValid(text)) {
            return new ObjectId(text);
        }
        return refuse(field, "a 24 character hex id");
    }

    private static Object instant(Object value, String field) {
        if (value instanceof Date) {
            return value;
        }
        if (value instanceof Instant instant) {
            return Date.from(instant);
        }
        if (value instanceof LocalDate day) {
            return Date.from(day.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        if (value instanceof String text) {
            try {
                return Date.from(OffsetDateTime.parse(text).toInstant());
            } catch (DateTimeParseException _) {
                try {
                    return Date.from(Instant.parse(text));
                } catch (DateTimeParseException _) {
                    return refuse(field, "an ISO-8601 timestamp");
                }
            }
        }
        return refuse(field, "an ISO-8601 timestamp");
    }

    private static Object refuse(String field, String expected) {
        throw new RequestException("The value for \"" + field + "\" must be " + expected);
    }
}
