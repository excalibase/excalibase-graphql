import { rolesOf, type PermissionsDoc } from './docmodel';

const DEFAULT_ROLES = ['anon', 'user'];

/** The roles the console lists: those the rules name, the ones added here, and anon and user when there are none. */
export function listRoles(doc: PermissionsDoc | null, added: string[]): string[] {
  const named = [...new Set([...(doc ? rolesOf(doc) : []), ...added])].sort();
  return named.length === 0 ? DEFAULT_ROLES : named;
}

/** How many tables a role has any rule on. */
export function tableCount(doc: PermissionsDoc | null, role: string): number {
  return new Set((doc?.tables ?? []).filter((rule) => rule.role === role).map((rule) => rule.table)).size;
}

/**
 * The columns of a table that a role cannot read, or `all` when it cannot read the table at all. Null when it cannot
 * be told (a rule written in a way this page does not read).
 */
export function hiddenColumns(doc: PermissionsDoc, table: string, role: string, columns: string[]): string[] | 'all' | null {
  const rule = (doc.tables ?? []).find((entry) => entry.table === table && entry.role === role);
  const select = rule?.select as { columns?: unknown } | undefined;
  if (!select) {
    return 'all';
  }
  if (select.columns === '*') {
    return [];
  }
  if (Array.isArray(select.columns)) {
    const allowed = new Set(select.columns.map(String));
    return columns.filter((column) => !allowed.has(column));
  }
  return null;
}
