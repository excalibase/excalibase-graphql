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

export function serialize(tree) {
  return JSON.stringify(tree, null, 2) + "\n";
}

/** The file name and type an export carries: a JSON document stays JSON, anything else is YAML text. */
export function exportName(text) {
  return text.trimStart().startsWith("{")
    ? { filename: "permissions.json", type: "application/json" }
    : { filename: "permissions.yaml", type: "text/yaml" };
}
