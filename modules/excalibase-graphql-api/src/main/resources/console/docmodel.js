// Pure edits of the permissions tree. Every function returns a new tree; the one that was passed in is never changed.

export const OPERATIONS = ["select", "insert", "update", "delete"];

const DEFAULTS = {
  select: { filter: {}, columns: "*" },
  insert: { check: {}, columns: "*" },
  update: { filter: {}, check: {}, columns: "*" },
  delete: { filter: {} }
};
const ROLE_NAME = /^[a-z][a-z0-9_]{0,62}$/;
const RESERVED_ROLE = "service";

export function validRole(name) {
  return ROLE_NAME.test(name) && name !== RESERVED_ROLE;
}

const rulesOf = (tree) => (Array.isArray(tree.tables) ? tree.tables : []);

export function rolesOf(tree) {
  return [...new Set(rulesOf(tree).map((rule) => rule.role))].sort();
}

/** The tables the grid lists: every one the schema reflects, and every one a rule names that the schema lacks. */
export function tableKeysOf(schemaTables, tree) {
  const reflected = schemaTables.map((table) => table.schema + "." + table.name);
  return [...new Set([...reflected, ...rulesOf(tree).map((rule) => rule.table)])].sort();
}

export function ruleOf(tree, table, role) {
  return rulesOf(tree).find((rule) => rule.table === table && rule.role === role) || null;
}

export function allows(tree, table, role, operation) {
  const rule = ruleOf(tree, table, role);
  return rule !== null && rule[operation] !== undefined;
}

/** A rule that is not the plain "every row, every column" the grid writes: it is edited as text. */
export function isCustom(tree, table, role, operation) {
  const rule = ruleOf(tree, table, role);
  const found = rule ? rule[operation] : undefined;
  return found !== undefined && JSON.stringify(found) !== JSON.stringify(DEFAULTS[operation]);
}

const omit = (object, key) => Object.fromEntries(Object.entries(object).filter(([name]) => name !== key));

function withRule(tree, table, role, change) {
  const rules = rulesOf(tree);
  const exists = rules.some((rule) => rule.table === table && rule.role === role);
  const next = exists
    ? rules.map((rule) => (rule.table === table && rule.role === role ? change(rule) : rule))
    : [...rules, change({ table, role })];
  return { ...tree, tables: next.filter((rule) => OPERATIONS.some((operation) => rule[operation] !== undefined)) };
}

export function setOperation(tree, table, role, operation, on) {
  return withRule(tree, table, role, (rule) => {
    const rest = omit(rule, operation);
    return on ? { ...rest, [operation]: rule[operation] ?? structuredClone(DEFAULTS[operation]) } : rest;
  });
}

export function withJwt(tree, jwt) {
  return jwt ? { ...omit(tree, "jwt"), jwt } : omit(tree, "jwt");
}

const OPERATION_MARKS = [["gained", "+"], ["lost", "-"], ["changed", "~"]];

function operationChanges(before, after, table, role) {
  const gained = OPERATIONS.filter((operation) => !allows(before, table, role, operation) && allows(after, table, role, operation));
  const lost = OPERATIONS.filter((operation) => allows(before, table, role, operation) && !allows(after, table, role, operation));
  const changed = OPERATIONS.filter((operation) => allows(before, table, role, operation) && allows(after, table, role, operation)
    && JSON.stringify(ruleOf(before, table, role)[operation]) !== JSON.stringify(ruleOf(after, table, role)[operation]));
  return { gained, lost, changed };
}

const TABLE_KEYS = new Set(["tables", "functions", "functionPermissions", "jwt"]);
const listOf = (tree, key) => (Array.isArray(tree[key]) ? tree[key] : []);
const same = (left, right) => JSON.stringify(left ?? null) === JSON.stringify(right ?? null);
const byName = (left, right) => left.table.localeCompare(right.table) || String(left.role).localeCompare(String(right.role));

function tableLines(before, after) {
  const pairs = new Map();
  [...rulesOf(before), ...rulesOf(after)].forEach((rule) => pairs.set(rule.table + "\u0000" + rule.role, rule));
  return [...pairs.values()]
    .map((rule) => ({ role: rule.role, table: rule.table, ...operationChanges(before, after, rule.table, rule.role) }))
    .filter((change) => change.gained.length + change.lost.length + change.changed.length > 0)
    .sort(byName)
    .map((change) => ({ ...change, text: change.role + " \u00b7 " + change.table + ": "
      + OPERATION_MARKS.flatMap(([key, mark]) => change[key].map((operation) => mark + " " + operation)).join(", ") }));
}

/** Per (function, role): a new permission is a gain, a removed one a loss, an edited one a change. */
function functionPermissionLines(before, after) {
  const key = (entry) => entry.function + "\u0000" + entry.role;
  const was = new Map(listOf(before, "functionPermissions").map((entry) => [key(entry), entry]));
  const now = new Map(listOf(after, "functionPermissions").map((entry) => [key(entry), entry]));
  return [...new Set([...was.keys(), ...now.keys()])].map((pair) => {
    const entry = now.get(pair) ?? was.get(pair);
    const kind = !was.has(pair) ? "gained" : (!now.has(pair) ? "lost" : (same(was.get(pair), now.get(pair)) ? null : "changed"));
    return kind && { role: entry.role, table: entry.function, gained: [], lost: [], changed: [], [kind]: ["execute"],
      text: entry.role + " \u00b7 " + entry.function + ": " + { gained: "+", lost: "-", changed: "~" }[kind] + " execute" };
  }).filter(Boolean).sort(byName);
}

/** A tracked function added or edited (exposedAs, inferPermissions, sessionArgument) is a changed rule; removing one is not. */
function functionLines(before, after) {
  const was = new Map(listOf(before, "functions").map((entry) => [entry.function, entry]));
  const now = new Map(listOf(after, "functions").map((entry) => [entry.function, entry]));
  return [...new Set([...was.keys(), ...now.keys()])].sort().map((name) => {
    if (same(was.get(name), now.get(name))) {
      return null;
    }
    const verb = !was.has(name) ? "added" : (!now.has(name) ? "removed" : "changed");
    const line = { role: null, table: name, gained: [], lost: [], changed: [], text: "function " + name + ": " + verb };
    return verb === "removed" ? { ...line, lost: ["function"] } : { ...line, changed: ["function"], other: true };
  }).filter(Boolean);
}

/** The token settings and every top-level key this page does not know: a key the grammar adds later still asks. */
function otherLines(before, after) {
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
export function describeChanges(before, after) {
  return [...tableLines(before, after), ...functionPermissionLines(before, after), ...functionLines(before, after),
    ...otherLines(before, after)];
}

/** True when a save gives a role more than it had: a gained operation, a rewritten rule or other token settings. */
export function widens(changes) {
  return changes.some((change) => change.gained.length > 0 || change.changed.length > 0 || change.other === true);
}

export function serialize(tree) {
  return JSON.stringify(tree, null, 2) + "\n";
}

/** True when saving the tree as the grid writes it would change the text beyond its content: comments, layout, order. */
export function rewriteLosesText(text, tree) {
  return text.trim() !== serialize(tree).trim();
}

/** The file name and type an export carries: a JSON text stays JSON, anything else is YAML. A label tells two files apart. */
export function exportName(text, label = "") {
  const stem = label === "" ? "permissions" : "permissions-" + label;
  return text.trimStart().startsWith("{")
    ? { filename: stem + ".json", type: "application/json" }
    : { filename: stem + ".yaml", type: "text/yaml" };
}
