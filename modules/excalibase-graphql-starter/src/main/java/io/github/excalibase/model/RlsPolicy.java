package io.github.excalibase.model;

import java.util.List;

/**
 * Represents a PostgreSQL Row Level Security (RLS) policy.
 */
public class RlsPolicy {
    private final String policyName;
    private final String tableName;
    private final String schemaName;
    private final boolean permissive;           // true = PERMISSIVE, false = RESTRICTIVE
    private final List<String> roles;           // Roles this policy applies to
    private final String command;               // ALL, SELECT, INSERT, UPDATE, DELETE
    private final String usingExpression;       // USING clause (for SELECT/UPDATE/DELETE)
    private final String withCheckExpression;   // WITH CHECK clause (for INSERT/UPDATE)

    public RlsPolicy(String policyName, String tableName, String schemaName,
                     boolean permissive, List<String> roles, String command,
                     String usingExpression, String withCheckExpression) {
        this.policyName = policyName;
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.permissive = permissive;
        this.roles = roles != null ? List.copyOf(roles) : List.of();
        this.command = command;
        this.usingExpression = usingExpression;
        this.withCheckExpression = withCheckExpression;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public boolean isPermissive() {
        return permissive;
    }

    public List<String> getRoles() {
        return roles;
    }

    public String getCommand() {
        return command;
    }

    public String getUsingExpression() {
        return usingExpression;
    }

    public String getWithCheckExpression() {
        return withCheckExpression;
    }

    public boolean appliesToRole(String roleName) {
        return roles.contains(roleName) || roles.contains("public");
    }

    public boolean appliesToCommand(String cmd) {
        return "ALL".equalsIgnoreCase(command) || command.equalsIgnoreCase(cmd);
    }

    @Override
    public String toString() {
        return String.format("RlsPolicy{name='%s', table='%s.%s', permissive=%s, roles=%s, command='%s'}",
                policyName, schemaName, tableName, permissive, roles, command);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        RlsPolicy rlsPolicy = (RlsPolicy) o;
        return policyName.equals(rlsPolicy.policyName) &&
               tableName.equals(rlsPolicy.tableName) &&
               schemaName.equals(rlsPolicy.schemaName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(policyName, tableName, schemaName);
    }
} 