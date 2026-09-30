package io.github.excalibase.access;

import io.github.excalibase.permissions.TrackedFunction;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.ExposedFunctions;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.schema.SchemaInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.github.excalibase.schema.GraphqlConstants.AGGREGATE_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.CONNECTION_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * Resolves a project's tracked functions against the reflected database (spec §6). A tracked
 * function that is not, or is no longer, a callable shape is logged as {@code function_invalid} and
 * never exposed: dropping it denies, which never widens what any role holds.
 */
final class FunctionResolver {

    private static final Logger log = LoggerFactory.getLogger(FunctionResolver.class);

    /** A GraphQL name a client can pass an argument under. */
    private static final Pattern ARGUMENT_NAME = Pattern.compile("[A-Za-z_]\\w{0,62}");

    /** Names a table field or a REST read already gives meaning to, so no argument may take them. */
    private static final Set<String> RESERVED_ARGUMENTS = Set.of("where", "filter", "orderby", "limit", "offset",
            "distincton", "vector", "select", "order", "or", "and", "not", "first", "after");

    /** A type name as {@code format_type} spells it; the engine casts arguments to it. */
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z0-9_ .\"\\[\\](),]{1,200}");

    private static final Set<String> JSON_TYPES = Set.of("json", "jsonb");

    private final SchemaInfo reflected;

    private FunctionResolver(SchemaInfo reflected) {
        this.reflected = reflected;
    }

    /**
     * @param callable whether the role may call a valid function: its return table and permissions
     */
    static ExposedFunctions resolve(SchemaInfo reflected, List<TrackedFunction> tracked,
                                    Predicate<ExposedFunction> callable) {
        FunctionResolver resolver = new FunctionResolver(reflected);
        Set<String> takenFields = resolver.tableFields();
        List<ExposedFunction> exposed = new ArrayList<>();
        for (TrackedFunction function : tracked) {
            resolver.valid(function)
                    .filter(valid -> resolver.fieldIsFree(valid, takenFields))
                    .filter(callable)
                    .ifPresent(exposed::add);
        }
        return ExposedFunctions.of(exposed);
    }

    private Optional<ExposedFunction> valid(TrackedFunction tracked) {
        SchemaInfo.FunctionInfo info = reflected.getFunction(tracked.function());
        String reason = invalidReason(tracked, info);
        if (reason != null) {
            invalid(tracked.function(), reason);
            return Optional.empty();
        }
        List<ExposedFunction.Argument> arguments = new ArrayList<>();
        ExposedFunction.SessionArgument session = null;
        for (SchemaInfo.FunctionArg arg : info.args()) {
            if (arg.name().equals(tracked.sessionArgument())) {
                session = new ExposedFunction.SessionArgument(arg.name(), arg.type());
            } else {
                arguments.add(new ExposedFunction.Argument(arg.name(), arg.type(), !arg.hasDefault()));
            }
        }
        ExposedFunction.Operation operation = tracked.exposedAs() == TrackedFunction.ExposedAs.QUERY
                ? ExposedFunction.Operation.QUERY : ExposedFunction.Operation.MUTATION;
        return Optional.of(new ExposedFunction(tracked.function(), operation,
                NamingUtils.fieldNameOf(tracked.function()), info.returnTable(), info.returnsSet(), arguments, session,
                info.securityDefiner()));
    }

    /** Why {@code tracked} cannot be served against the live catalog, or null when it can. */
    private String invalidReason(TrackedFunction tracked, SchemaInfo.FunctionInfo info) {
        if (info == null) {
            return "no such function";
        }
        if (info.kind() != SchemaInfo.RoutineKind.FUNCTION) {
            return "a procedure";
        }
        if (info.overloads() > 1) {
            return "overloaded";
        }
        if (info.returnTable() == null || !reflected.hasTable(info.returnTable())) {
            return "does not return rows of a served table or view";
        }
        if (!volatilityMatches(tracked.exposedAs(), info.volatility())) {
            return "exposedAs " + tracked.exposedAs() + " does not match volatility " + info.volatility();
        }
        String argumentReason = argumentsReason(info.args());
        return argumentReason != null ? argumentReason : sessionArgumentReason(tracked.sessionArgument(), info.args());
    }

    /** QUERY ⇔ STABLE/IMMUTABLE, MUTATION ⇔ VOLATILE; an unknown volatility matches neither. */
    private static boolean volatilityMatches(TrackedFunction.ExposedAs exposedAs, SchemaInfo.Volatility volatility) {
        if (volatility == null) {
            return false;
        }
        boolean isVolatile = volatility == SchemaInfo.Volatility.VOLATILE;
        return exposedAs == TrackedFunction.ExposedAs.MUTATION ? isVolatile : !isVolatile;
    }

    private static String argumentsReason(List<SchemaInfo.FunctionArg> args) {
        for (SchemaInfo.FunctionArg arg : args) {
            if (!"i".equals(arg.mode())) {
                return "argument mode " + arg.mode() + " is not supported";
            }
            if (!ARGUMENT_NAME.matcher(arg.name()).matches()) {
                return "an argument has no usable name";
            }
            if (RESERVED_ARGUMENTS.contains(arg.name().toLowerCase(Locale.ROOT))) {
                return "argument " + arg.name() + " takes a reserved name";
            }
            if (!TYPE_NAME.matcher(arg.type()).matches()) {
                return "argument " + arg.name() + " has an unsupported type";
            }
        }
        return null;
    }

    private static String sessionArgumentReason(String sessionArgument, List<SchemaInfo.FunctionArg> args) {
        if (sessionArgument == null) {
            return null;
        }
        Optional<SchemaInfo.FunctionArg> declared = args.stream()
                .filter(arg -> arg.name().equals(sessionArgument))
                .findFirst();
        if (declared.isEmpty()) {
            return "sessionArgument is not an argument";
        }
        return JSON_TYPES.contains(declared.get().type()) ? null : "sessionArgument is not json or jsonb";
    }

    /** A function may not shadow a field a table (or an earlier function) already gives the schema. */
    private boolean fieldIsFree(ExposedFunction function, Set<String> takenFields) {
        if (takenFields.add(function.fieldName())) {
            return true;
        }
        invalid(function.function(), "field name " + function.fieldName() + " is taken");
        return false;
    }

    /** Every root field a reflected table may contribute, whichever role is served. */
    private Set<String> tableFields() {
        Set<String> names = new HashSet<>();
        for (String table : reflected.getTableNames()) {
            String field = NamingUtils.fieldNameOf(table);
            String type = NamingUtils.typeNameOf(table);
            names.addAll(List.of(field, field + CONNECTION_SUFFIX, field + AGGREGATE_SUFFIX, CREATE_PREFIX + type,
                    CREATE_MANY_PREFIX + type, UPDATE_PREFIX + type, DELETE_PREFIX + type));
        }
        return names;
    }

    private static void invalid(String function, String reason) {
        log.warn("function_invalid function={} reason={}", function, reason);
    }
}
