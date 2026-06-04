package io.github.excalibase.rls;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class VariableResolver {

    private final UserContext context;
    private final Instant nowSnapshot;
    private final LocalDate todaySnapshot;

    public VariableResolver(UserContext context) {
        this.context = context;
        this.nowSnapshot = Instant.now();
        this.todaySnapshot = LocalDate.now();
    }

    public Object resolve(String value, FieldType fieldType) {
        if (value == null) return null;
        if (isVariable(value)) return resolveVariable(unwrap(value));
        return cast(value, fieldType);
    }

    public Collection<Object> resolveList(String value, FieldType fieldType) {
        if (value == null) return List.of();
        if (isVariable(value)) {
            Object resolved = resolveVariable(unwrap(value));
            if (resolved instanceof Collection<?> col) {
                List<Object> out = new ArrayList<>(col.size());
                for (Object o : col) out.add(o);
                return out;
            }
            return List.of(resolved);
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .map(v -> (Object) cast(v, fieldType))
            .collect(Collectors.toList());
    }

    private static boolean isVariable(String value) {
        return value.startsWith("{{") && value.endsWith("}}");
    }

    private static String unwrap(String value) {
        return value.substring(2, value.length() - 2).trim();
    }

    private Object resolveVariable(String name) {
        return switch (name) {
            case "currentUserId" -> uuidFrom(context.userId());
            case "currentTenantId" -> uuidFrom(context.tenantId());
            case "currentUserRoles" -> context.roles();
            case "currentUserGroupIds" -> context.groupIds();
            case "now" -> nowSnapshot;
            case "today" -> todaySnapshot;
            default -> {
                if (name.startsWith("daysAgo:")) {
                    int days = Integer.parseInt(name.substring("daysAgo:".length()));
                    yield nowSnapshot.minus(days, ChronoUnit.DAYS);
                }
                Object custom = context.resolveVariable(name);
                if (custom != null) yield custom;
                throw new IllegalArgumentException("Unknown variable: {{" + name + "}}");
            }
        };
    }

    private static UUID uuidFrom(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static Object cast(String value, FieldType fieldType) {
        return switch (fieldType) {
            case STRING -> value;
            case UUID -> UUID.fromString(value);
            case INTEGER -> Integer.parseInt(value);
            case LONG -> Long.parseLong(value);
            case BOOLEAN -> Boolean.parseBoolean(value);
            case DOUBLE -> Double.parseDouble(value);
            case DATE -> LocalDate.parse(value);
            case DATETIME -> parseDatetime(value);
        };
    }

    private static Instant parseDatetime(String s) {
        // Accept both ISO_INSTANT (Instant.toString → "...Z") and naive
        // LocalDateTime ("yyyy-MM-ddTHH:mm:ss[.fff]"); coerce to Instant.
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException ignored) {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        }
    }
}
