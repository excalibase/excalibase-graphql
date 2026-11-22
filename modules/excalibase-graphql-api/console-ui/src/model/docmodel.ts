// Pure edits of the permissions tree. Every function returns a new tree; the one that was passed in is never changed.

export const OPERATIONS = ["select", "insert", "update", "delete"] as const;
export type Operation = (typeof OPERATIONS)[number];

export interface Rule { table: string; role: string; [operation: string]: unknown }
/** The permissions document as the engine's /parse hands it back. Keys this page does not know are kept as they are. */
export interface Tree { tables?: unknown; jwt?: unknown; [key: string]: unknown }
export interface SchemaTable { schema: string; name: string }
export interface Change {
  role: string | null; table: string | null; gained: string[]; lost: string[]; changed: string[]; text: string; other?: boolean;
}

const DEFAULTS: Record<Operation, unknown> = {
  select: { filter: {}, columns: "*" },
  insert: { check: {}, columns: "*" },
  update: { filter: {}, check: {}, columns: "*" },
  delete: { filter: {} }
};
const ROLE_NAME = /^[a-z][a-z0-9_]{0,62}$/;
const RESERVED_ROLE = "service";

export function validRole(name: string): boolean {
  return ROLE_NAME.test(name) && name !== RESERVED_ROLE;
}

const rulesOf = (tree: Tree): Rule[] => (Array.isArray(tree.tables) ? (tree.tables as Rule[]) : []);

export function rolesOf(tree: Tree): string[] {
  return [...new Set(rulesOf(tree).map((rule) => rule.role))].sort();
}

/** The tables the grid lists: every one the schema reflects, and every one a rule names that the schema lacks. */
export function tableKeysOf(schemaTables: SchemaTable[], tree: Tree): string[] {
  const reflected = schemaTables.map((table) => table.schema + "." + table.name);
  return [...new Set([...reflected, ...rulesOf(tree).map((rule) => rule.table)])].sort();
}

export function ruleOf(tree: Tree, table: string, role: string): Rule | null {
  return rulesOf(tree).find((rule) => rule.table === table && rule.role === role) || null;
}

export function allows(tree: Tree, table: string, role: string, operation: Operation): boolean {
  const rule = ruleOf(tree, table, role);
  return rule !== null && rule[operation] !== undefined;
}

/** A rule that is not the plain "every row, every column" the grid writes: it is edited as text. */
export function isCustom(tree: Tree, table: string, role: string, operation: Operation): boolean {
  const rule = ruleOf(tree, table, role);
  const found = rule ? rule[operation] : undefined;
  return found !== undefined && JSON.stringify(found) !== JSON.stringify(DEFAULTS[operation]);
}

const omit = (object: Record<string, unknown>, key: string): Record<string, unknown> => Object.fromEntries(Object.entries(object).filter(([name]) => name !== key));

function withRule(tree: Tree, table: string, role: string, change: (rule: Rule) => Rule): Tree {
  const rules = rulesOf(tree);
  const exists = rules.some((rule) => rule.table === table && rule.role === role);
  const next = exists
    ? rules.map((rule) => (rule.table === table && rule.role === role ? change(rule) : rule))
    : [...rules, change({ table, role })];
  return { ...tree, tables: next.filter((rule) => OPERATIONS.some((operation) => rule[operation] !== undefined)) };
}

export function setOperation(tree: Tree, table: string, role: string, operation: Operation, on: boolean): Tree {
  return withRule(tree, table, role, (rule) => {
    const rest = omit(rule, operation);
    return (on ? { ...rest, [operation]: rule[operation] ?? structuredClone(DEFAULTS[operation]) } : rest) as Rule;
  });
}

export function withJwt(tree: Tree, jwt: unknown): Tree {
  return jwt ? { ...omit(tree, "jwt"), jwt } : omit(tree, "jwt");
}

type Kind = "gained" | "lost" | "changed";
const OPERATION_MARKS: [Kind, string][] = [["gained", "+"], ["lost", "-"], ["changed", "~"]];

function operationChanges(before: Tree, after: Tree, table: string, role: string): Record<Kind, Operation[]> {
  const gained = OPERATIONS.filter((operation) => !allows(before, table, role, operation) && allows(after, table, role, operation));
  const lost = OPERATIONS.filter((operation) => allows(before, table, role, operation) && !allows(after, table, role, operation));
  const changed = OPERATIONS.filter((operation) => allows(before, table, role, operation) && allows(after, table, role, operation)
    && JSON.stringify(ruleOf(before, table, role)?.[operation]) !== JSON.stringify(ruleOf(after, table, role)?.[operation]));
  return { gained, lost, changed };
}

const TABLE_KEYS = new Set(["tables", "functions", "functionPermissions", "jwt"]);
const listOf = (tree: Tree, key: string): Record<string, string>[] => (Array.isArray(tree[key]) ? (tree[key] as Record<string, string>[]) : []);
const same = (left: unknown, right: unknown): boolean => JSON.stringify(left ?? null) === JSON.stringify(right ?? null);
const byName = (left: Change, right: Change): number => String(left.table).localeCompare(String(right.table)) || String(left.role).localeCompare(String(right.role));

function tableLines(before: Tree, after: Tree): Change[] {
  const pairs = new Map<string, Rule>();
  [...rulesOf(before), ...rulesOf(after)].forEach((rule) => pairs.set(rule.table + "\u0000" + rule.role, rule));
  return [...pairs.values()]
    .map((rule) => ({ role: rule.role, table: rule.table, ...operationChanges(before, after, rule.table, rule.role) }))
    .filter((change) => change.gained.length + change.lost.length + change.changed.length > 0)
    .map((change) => ({ ...change, text: change.role + " \u00b7 " + change.table + ": "
      + OPERATION_MARKS.flatMap(([key, mark]) => change[key].map((operation) => mark + " " + operation)).join(", ") }))
    .sort(byName);
}

/** Per (function, role): a new permission is a gain, a removed one a loss, an edited one a change. */
function functionPermissionLines(before: Tree, after: Tree): Change[] {
  const key = (entry: Record<string, string>) => entry.function + "\u0000" + entry.role;
  const was = new Map(listOf(before, "functionPermissions").map((entry) => [key(entry), entry]));
  const now = new Map(listOf(after, "functionPermissions").map((entry) => [key(entry), entry]));
  return [...new Set([...was.keys(), ...now.keys()])].map((pair): Change | null => {
    const entry = now.get(pair) ?? was.get(pair) ?? {};
    const kind = !was.has(pair) ? "gained" : (!now.has(pair) ? "lost" : (same(was.get(pair), now.get(pair)) ? null : "changed"));
    return kind && { role: entry.role ?? null, table: entry.function ?? null, gained: [], lost: [], changed: [], [kind]: ["execute"],
      text: entry.role + " \u00b7 " + entry.function + ": " + { gained: "+", lost: "-", changed: "~" }[kind] + " execute" };
  }).filter((line): line is Change => line !== null).sort(byName);
}

/** A tracked function added or edited (exposedAs, inferPermissions, sessionArgument) is a changed rule; removing one is not. */
function functionLines(before: Tree, after: Tree): Change[] {
  const was = new Map(listOf(before, "functions").map((entry) => [String(entry.function), entry]));
  const now = new Map(listOf(after, "functions").map((entry) => [String(entry.function), entry]));
  return [...new Set([...was.keys(), ...now.keys()])].sort().map((name): Change | null => {
    if (same(was.get(name), now.get(name))) {
      return null;
    }
    const verb = !was.has(name) ? "added" : (!now.has(name) ? "removed" : "changed");
    const line: Change = { role: null, table: name, gained: [], lost: [], changed: [], text: "function " + name + ": " + verb };
    return verb === "removed" ? { ...line, lost: ["function"] } : { ...line, changed: ["function"], other: true };
  }).filter((line): line is Change => line !== null);
}

/** The token settings and every top-level key this page does not know: a key the grammar adds later still asks. */
function otherLines(before: Tree, after: Tree): Change[] {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((key) => !TABLE_KEYS.has(key)).sort();
  const lines = keys.filter((key) => !same(before[key], after[key])).map((key) => ({ role: null, table: null, gained: [], lost: [],
    changed: [key], other: true, text: key + " changes" }));
  return same(before.jwt, after.jwt) ? lines
    : [...lines, { role: null, table: null, gained: [], lost: [], changed: [], other: true, text: "token settings change" }];
}

/**
 * What a save changes, as lines a person can read: per role and table the operations gained (+), lost (-) and
 * rewritten (~); per function its permissions and its entry; and a line for the token settings or any other top-level
 * key that differs. Only a line with a gain or a change, or an "other" line, widens access.
 */
export function describeChanges(before: Tree, after: Tree): Change[] {
  return [...tableLines(before, after), ...functionPermissionLines(before, after), ...functionLines(before, after),
    ...otherLines(before, after)];
}

/** True when a save gives a role more than it had: a gained operation, a rewritten rule or other token settings. */
export function widens(changes: Change[]): boolean {
  return changes.some((change) => change.gained.length > 0 || change.changed.length > 0 || change.other === true);
}

export function serialize(tree: Tree): string {
  return JSON.stringify(tree, null, 2) + "\n";
}

/** True when saving the tree as the grid writes it would change the text beyond its content: comments, layout, order. */
export function rewriteLosesText(text: string, tree: Tree): boolean {
  return text.trim() !== serialize(tree).trim();
}

/** The file name and type an export carries: a JSON text stays JSON, anything else is YAML. A label tells two files apart. */
export function exportName(text: string, label = ""): { filename: string; type: string } {
  const stem = label === "" ? "permissions" : "permissions-" + label;
  return text.trimStart().startsWith("{")
    ? { filename: stem + ".json", type: "application/json" }
    : { filename: stem + ".yaml", type: "text/yaml" };
}

export const SHORT: Record<Operation, string> = { select: "S", insert: "I", update: "U", delete: "D" };
const DEFAULT_ROLES = ["anon", "user"];

/** The roles the grid has a column for: those the rules name, the ones added here, and anon and user when empty. */
export function columnRoles(tree: Tree, added: string[]): string[] {
  const named = [...new Set([...rolesOf(tree), ...added])].sort();
  return named.length === 0 ? DEFAULT_ROLES : named;
}

export function roleProblem(name: string, known: string[]): string | null {
  if (!validRole(name)) {
    return "A role is lowercase letters, digits and underscores, starts with a letter, and is not service.";
  }
  return known.includes(name) ? "That role is already a column." : null;
}
