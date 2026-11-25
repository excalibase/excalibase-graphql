package io.github.excalibase.metadata;

import io.github.excalibase.access.RuleProblem;
import io.github.excalibase.permissions.PermissionSet;

import java.util.List;

/** Lists the rules of a parsed document that the loaded schema does not fit. It lives here so the admin layer never names the permission model. */
@FunctionalInterface
public interface SchemaCheck {

    List<RuleProblem> problems(PermissionSet document);
}
