package io.github.excalibase.access;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.PresetValue;
import io.github.excalibase.permissions.compile.FilterCompiler;
import io.github.excalibase.permissions.compile.ParamNamer;
import io.github.excalibase.permissions.compile.PresetValues;
import io.github.excalibase.permissions.compile.SessionBinding;
import io.github.excalibase.permissions.compile.SqlFragment;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.security.WriteGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One request's SQL guards: the plan's filters, checks and presets bound to the request's session
 * variables. A table or operation the plan does not hold is answered with FALSE, never with "no
 * restriction". Parameter names are unique across the request; not for use by two requests.
 */
public final class RequestGuard implements RlsWhereContributor, WriteGuard {

    private static final Contribution DENY = new Contribution("FALSE", Map.of());

    private final AccessPlan plan;
    private final SessionBinding binding;
    private final ParamNamer namer = new ParamNamer("perm");

    RequestGuard(AccessPlan plan, Map<String, String> sessionVariables) {
        this.plan = plan;
        this.binding = new SessionBinding(sessionVariables);
    }

    @Override
    public Contribution contribute(String tableName, RlsOp op) {
        return contribute(tableName, null, op);
    }

    /**
     * SELECT gets the select filter; UPDATE and DELETE get their own filter AND the select filter, so a
     * role never changes a row it cannot read.
     */
    @Override
    public Contribution contribute(String tableName, String outerAlias, RlsOp op) {
        if (plan.allAccess()) {
            return null;
        }
        Optional<TableRules> rules = plan.rules(tableName);
        if (rules.isEmpty()) {
            return DENY;
        }
        Optional<BoolExp> filter = filterFor(rules.get(), op);
        return filter.isPresent() ? compiled(filter.get(), tableName, outerAlias) : DENY;
    }

    @Override
    public Map<String, Object> presets(String tableName, RlsOp op) {
        if (plan.allAccess()) {
            return Map.of();
        }
        Map<String, PresetValue> presets = plan.rules(tableName).flatMap(rules -> switch (op) {
            case INSERT -> rules.insert().map(TableRules.InsertRule::presets);
            case UPDATE -> rules.update().map(TableRules.UpdateRule::presets);
            default -> Optional.<Map<String, PresetValue>>empty();
        }).orElse(Map.of());
        Map<String, Object> bound = new LinkedHashMap<>();
        presets.forEach((column, value) ->
                bound.put(column, PresetValues.bind(value, tableName, column, plan.reflected(), binding)));
        return bound;
    }

    @Override
    public Contribution check(String tableName, String alias, RlsOp op) {
        if (plan.allAccess()) {
            return null;
        }
        Optional<BoolExp> check = plan.rules(tableName).flatMap(rules -> switch (op) {
            case INSERT -> rules.insert().map(TableRules.InsertRule::check);
            case UPDATE -> rules.update().map(TableRules.UpdateRule::check);
            default -> Optional.<BoolExp>empty();
        });
        if (check.isEmpty()) {
            return DENY;
        }
        return compiled(check.get(), tableName, alias);
    }

    /** Empty (denied) for every read of a table the role may insert into but not select. */
    private static Optional<BoolExp> filterFor(TableRules rules, RlsOp op) {
        return rules.select().map(TableRules.SelectRule::filter).flatMap(select -> switch (op) {
            case SELECT -> Optional.of(select);
            case UPDATE -> rules.update().map(update -> both(update.filter(), select));
            case DELETE -> rules.delete().map(delete -> both(delete.filter(), select));
            case INSERT -> Optional.<BoolExp>empty();
        });
    }

    private static BoolExp both(BoolExp first, BoolExp second) {
        List<BoolExp> operands = new ArrayList<>();
        List.of(first, second).forEach(operand -> {
            if (!RulesResolver.isTrue(operand)) {
                operands.add(operand);
            }
        });
        return switch (operands.size()) {
            case 0 -> BoolExp.TRUE;
            case 1 -> operands.getFirst();
            default -> new BoolExp.And(operands);
        };
    }

    /** Null when the expression is {@code {}}: nothing to add to the query. */
    private synchronized Contribution compiled(BoolExp expression, String tableName, String alias) {
        if (RulesResolver.isTrue(expression)) {
            return null;
        }
        SqlFragment fragment = FilterCompiler.compile(expression, tableName, aliasFor(tableName, alias),
                plan.reflected(), binding, namer);
        return new Contribution(fragment.sql(), fragment.params());
    }

    /** An unaliased statement names the table by its bare, quoted name. */
    private static String aliasFor(String tableName, String alias) {
        if (alias != null) {
            return alias;
        }
        int dot = tableName.lastIndexOf('.');
        return "\"" + (dot < 0 ? tableName : tableName.substring(dot + 1)) + "\"";
    }
}
