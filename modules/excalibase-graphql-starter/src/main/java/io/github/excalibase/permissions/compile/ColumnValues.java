package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.compile.ColumnType.Kind;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Converts a value to the Java value of a column kind, accepting what JDBC returns, what a change
 * feed carries (text) and what a permission holds; the text forms follow Postgres input syntax.
 */
final class ColumnValues {

    private static final Pattern INTEGRAL = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern DECIMAL = Pattern.compile("^[+-]?(?:\\d++(?:\\.\\d*+)?|\\.\\d++)(?:[eE][+-]?\\d++)?$");
    private static final Map<String, String> FLOAT_SPECIALS = Map.of(
            "nan", "NaN", "infinity", "Infinity", "+infinity", "Infinity", "-infinity", "-Infinity");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Map<String, Boolean> BOOLEANS = Map.ofEntries(
            entry("true", true), entry("t", true), entry("yes", true), entry("y", true), entry("on", true),
            entry("1", true), entry("false", false), entry("f", false), entry("no", false), entry("n", false),
            entry("off", false), entry("0", false));
    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .appendPattern("[XXX][XX][X]")
            .toFormatter(Locale.ROOT);

    private ColumnValues() {
    }

    static Object convert(Kind kind, Object value) {
        if (value == null || value instanceof Collection<?> || value instanceof Map<?, ?>) {
            throw new IllegalArgumentException("Not a scalar value");
        }
        try {
            return switch (kind) {
                case SMALLINT -> integral(value, Short.MIN_VALUE, Short.MAX_VALUE);
                case INTEGER -> integral(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
                case BIGINT -> integral(value, Long.MIN_VALUE, Long.MAX_VALUE);
                case NUMERIC -> decimal(value);
                case REAL -> value instanceof Float number ? number : Float.parseFloat(floatText(value));
                case DOUBLE -> value instanceof Double number ? number : Double.parseDouble(floatText(value));
                case BOOLEAN -> bool(value);
                case UUID -> uuid(value);
                case TIMESTAMPTZ -> instant(value);
                case TIMESTAMP -> localDateTime(value);
                case DATE -> localDate(value);
                case TEXT, ENUM, OTHER -> value.toString();
            };
        } catch (ArithmeticException | DateTimeException e) {
            throw new IllegalArgumentException("Not a valid " + kind, e);
        }
    }

    private static long integral(Object value, long min, long max) {
        long number;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            number = ((Number) value).longValue();
        } else if (value instanceof BigDecimal decimal) {
            number = decimal.toBigIntegerExact().longValueExact();
        } else if (value instanceof BigInteger big) {
            number = big.longValueExact();
        } else if (value instanceof String text && INTEGRAL.matcher(text).matches()) {
            number = new BigInteger(text).longValueExact();
        } else {
            throw new IllegalArgumentException("Not an integer");
        }
        if (number < min || number > max) {
            throw new IllegalArgumentException("Out of range");
        }
        return number;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof BigInteger) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String text && DECIMAL.matcher(text).matches()) {
            return new BigDecimal(text);
        }
        throw new IllegalArgumentException("Not a number");
    }

    private static String floatText(Object value) {
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof String text) {
            if (DECIMAL.matcher(text).matches()) {
                return text;
            }
            String special = FLOAT_SPECIALS.get(text.toLowerCase(Locale.ROOT));
            if (special != null) {
                return special;
            }
        }
        throw new IllegalArgumentException("Not a number");
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        Boolean flag = value instanceof String text ? BOOLEANS.get(text.trim().toLowerCase(Locale.ROOT)) : null;
        if (flag == null) {
            throw new IllegalArgumentException("Not a boolean");
        }
        return flag;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        if (value instanceof String text && UUID_TEXT.matcher(text).matches()) {
            return UUID.fromString(text);
        }
        throw new IllegalArgumentException("Not a uuid");
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime dateTime -> dateTime.toInstant();
            case ZonedDateTime dateTime -> dateTime.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case String text -> OffsetDateTime.from(DATE_TIME.parse(text.trim())).toInstant();
            default -> throw new IllegalArgumentException("Not a timestamp");
        };
    }

    private static LocalDateTime localDateTime(Object value) {
        return switch (value) {
            case LocalDateTime dateTime -> dateTime;
            case Timestamp timestamp -> timestamp.toLocalDateTime();
            case String text when DATE_ONLY.matcher(text.trim()).matches() -> LocalDate.parse(text.trim()).atStartOfDay();
            // Postgres drops an offset given for a timestamp without time zone, and so does this.
            case String text -> LocalDateTime.from(DATE_TIME.parse(text.trim()));
            default -> throw new IllegalArgumentException("Not a timestamp");
        };
    }

    private static LocalDate localDate(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case Date date -> date.toLocalDate();
            case String text -> LocalDate.parse(text.trim());
            default -> throw new IllegalArgumentException("Not a date");
        };
    }
}
