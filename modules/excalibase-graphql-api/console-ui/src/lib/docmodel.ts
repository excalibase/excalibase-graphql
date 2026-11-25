// Pure edits of the permissions document. Every function returns a new value; the one that was passed in is never changed.

export const OPERATIONS = ['select', 'insert', 'update', 'delete'] as const;
export type Operation = (typeof OPERATIONS)[number];

export interface Rule {
  table: string;
  role: string;
  select?: unknown;
  insert?: unknown;
  update?: unknown;
  delete?: unknown;
}

export interface JwtSection {
  issuer?: string;
  jwksUrl?: string;
  secret?: string;
  audience?: string | string[];
  claims?: { role?: string; defaultRole?: string; userId?: string; extra?: Record<string, string> };
  [key: string]: unknown;
}

export interface FunctionPermission {
  function: string;
  role: string;
  [key: string]: unknown;
}

export interface PermissionsDoc {
  tables?: Rule[];
  functions?: Array<{ function: string; [key: string]: unknown }>;
  functionPermissions?: FunctionPermission[];
  jwt?: JwtSection;
  [key: string]: unknown;
}

export interface SchemaTableRef {
  schema: string;
  name: string;
}

export interface Change {
  role: string | null;
  table: string | null;
  gained: string[];
  lost: string[];
  changed: string[];
  other?: boolean;
  text: string;
}


const DEFAULTS: Record<Operation, unknown> = {
  select: { filter: {}, columns: '*' },
  insert: { check: {}, columns: '*' },
  update: { filter: {}, check: {}, columns: '*' },
  delete: { filter: {} },
};
const ROLE_NAME = /^[a-z][a-z0-9_]{0,62}$/;
const RESERVED_ROLE = 'service';

export function validRole(name: string): boolean {
  return ROLE_NAME.test(name) && name !== RESERVED_ROLE;
}

const rulesOf = (doc: PermissionsDoc): Rule[] => (Array.isArray(doc.tables) ? doc.tables : []);

export function rolesOf(doc: PermissionsDoc): string[] {
  return [...new Set(rulesOf(doc).map((rule) => rule.role))].sort();
}

/** The tables the grid lists: every one the schema reflects, and every one a rule names that the schema lacks. */
export function tableKeysOf(schemaTables: SchemaTableRef[], doc: PermissionsDoc): string[] {
  const reflected = schemaTables.map((table) => `${table.schema}.${table.name}`);
  return [...new Set([...reflected, ...rulesOf(doc).map((rule) => rule.table)])].sort();
}

export function ruleOf(doc: PermissionsDoc, table: string, role: string): Rule | null {
  return rulesOf(doc).find((rule) => rule.table === table && rule.role === role) ?? null;
}

export function allows(doc: PermissionsDoc, table: string, role: string, operation: Operation): boolean {
  const rule = ruleOf(doc, table, role);
  return rule !== null && rule[operation] !== undefined;
}

/** A rule that is not the plain "every row, every column" the grid writes: it is edited as text. */
export function isCustom(doc: PermissionsDoc, table: string, role: string, operation: Operation): boolean {
  const found = ruleOf(doc, table, role)?.[operation];
  return found !== undefined && JSON.stringify(found) !== JSON.stringify(DEFAULTS[operation]);
}

const omit = <T extends object>(object: T, key: string): T =>
  Object.fromEntries(Object.entries(object).filter(([name]) => name !== key)) as T;

function withRule(doc: PermissionsDoc, table: string, role: string, change: (rule: Rule) => Rule): PermissionsDoc {
  const rules = rulesOf(doc);
  const exists = rules.some((rule) => rule.table === table && rule.role === role);
  const next = exists
    ? rules.map((rule) => (rule.table === table && rule.role === role ? change(rule) : rule))
    : [...rules, change({ table, role })];
  return { ...doc, tables: next.filter((rule) => OPERATIONS.some((operation) => rule[operation] !== undefined)) };
}

export function setOperation(doc: PermissionsDoc, table: string, role: string, operation: Operation, on: boolean): PermissionsDoc {
  return withRule(doc, table, role, (rule) => {
    const rest = omit(rule, operation);
    return on ? { ...rest, [operation]: rule[operation] ?? structuredClone(DEFAULTS[operation]) } : rest;
  });
}

/** The document without its token section: the console only reads a section an older document holds, it never writes one. */
export function withoutJwt(doc: PermissionsDoc): PermissionsDoc {
  return omit(doc, 'jwt');
}

const OPERATION_MARKS = [
  ['gained', '+'],
  ['lost', '-'],
  ['changed', '~'],
] as const;

function operationChanges(before: PermissionsDoc, after: PermissionsDoc, table: string, role: string) {
  const gained = OPERATIONS.filter((operation) => !allows(before, table, role, operation) && allows(after, table, role, operation));
  const lost = OPERATIONS.filter((operation) => allows(before, table, role, operation) && !allows(after, table, role, operation));
  const changed = OPERATIONS.filter(
    (operation) =>
      allows(before, table, role, operation) &&
      allows(after, table, role, operation) &&
      JSON.stringify(ruleOf(before, table, role)?.[operation]) !== JSON.stringify(ruleOf(after, table, role)?.[operation]),
  );
  return { gained, lost, changed };
}

const TABLE_KEYS = new Set(['tables', 'functions', 'functionPermissions', 'jwt']);
const listOf = <K extends keyof PermissionsDoc>(doc: PermissionsDoc, key: K): NonNullable<PermissionsDoc[K]> =>
  (Array.isArray(doc[key]) ? doc[key] : []) as NonNullable<PermissionsDoc[K]>;
const same = (left: unknown, right: unknown): boolean => JSON.stringify(left ?? null) === JSON.stringify(right ?? null);
const byName = (left: Change, right: Change): number =>
  (left.table ?? '').localeCompare(right.table ?? '') || String(left.role).localeCompare(String(right.role));

function tableLines(before: PermissionsDoc, after: PermissionsDoc): Change[] {
  const pairs = new Map<string, Rule>();
  [...rulesOf(before), ...rulesOf(after)].forEach((rule) => pairs.set(`${rule.table}\u0000${rule.role}`, rule));
  return [...pairs.values()]
    .map((rule) => ({ role: rule.role, table: rule.table, ...operationChanges(before, after, rule.table, rule.role) }))
    .filter((change) => change.gained.length + change.lost.length + change.changed.length > 0)
    .map((change): Change => ({
      ...change,
      text: `${change.role} \u00b7 ${change.table}: ${OPERATION_MARKS.flatMap(([key, mark]) => change[key].map((operation) => `${mark} ${operation}`)).join(', ')}`,
    }))
    .sort(byName);
}

const EMPTY = { gained: [], lost: [], changed: [] };

/** Per (function, role): a new permission is a gain, a removed one a loss, an edited one a change. */
function functionPermissionLines(before: PermissionsDoc, after: PermissionsDoc): Change[] {
  const key = (entry: FunctionPermission) => `${entry.function}\u0000${entry.role}`;
  const was = new Map(listOf(before, 'functionPermissions').map((entry) => [key(entry), entry]));
  const now = new Map(listOf(after, 'functionPermissions').map((entry) => [key(entry), entry]));
  const marks = { gained: '+', lost: '-', changed: '~' } as const;
  return [...new Set([...was.keys(), ...now.keys()])]
    .map((pair): Change | null => {
      const entry = now.get(pair) ?? was.get(pair);
      if (!entry) {
        return null;
      }
      const kind = !was.has(pair) ? 'gained' : !now.has(pair) ? 'lost' : same(was.get(pair), now.get(pair)) ? null : 'changed';
      return kind
        ? { role: entry.role, table: entry.function, ...EMPTY, [kind]: ['execute'], text: `${entry.role} \u00b7 ${entry.function}: ${marks[kind]} execute` }
        : null;
    })
    .filter((line): line is Change => line !== null)
    .sort(byName);
}

/** A tracked function added or edited (exposedAs, inferPermissions, sessionArgument) is a changed rule; removing one is not. */
function functionLines(before: PermissionsDoc, after: PermissionsDoc): Change[] {
  const was = new Map(listOf(before, 'functions').map((entry) => [entry.function, entry]));
  const now = new Map(listOf(after, 'functions').map((entry) => [entry.function, entry]));
  return [...new Set([...was.keys(), ...now.keys()])]
    .sort()
    .map((name): Change | null => {
      if (same(was.get(name), now.get(name))) {
        return null;
      }
      const verb = !was.has(name) ? 'added' : !now.has(name) ? 'removed' : 'changed';
      const line: Change = { role: null, table: name, ...EMPTY, text: `function ${name}: ${verb}` };
      return verb === 'removed' ? { ...line, lost: ['function'] } : { ...line, changed: ['function'], other: true };
    })
    .filter((line): line is Change => line !== null);
}

/** The token settings and every top-level key this page does not know: a key the grammar adds later still asks. */
function otherLines(before: PermissionsDoc, after: PermissionsDoc): Change[] {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((key) => !TABLE_KEYS.has(key)).sort();
  const lines = keys
    .filter((key) => !same(before[key], after[key]))
    .map((key): Change => ({ role: null, table: null, ...EMPTY, changed: [key], other: true, text: `${key} changes` }));
  return same(before.jwt, after.jwt)
    ? lines
    : [...lines, { role: null, table: null, ...EMPTY, other: true, text: 'token settings change' }];
}

/**
 * What a save changes, as lines a person can read: per role and table the operations gained (+), lost (-) and
 * rewritten (~); per function its permissions and its entry; and a line for the token settings or any other top-level
 * key that differs. Only a line with a gain or a change, or an "other" line, widens access.
 */
export function describeChanges(before: PermissionsDoc, after: PermissionsDoc): Change[] {
  return [...tableLines(before, after), ...functionPermissionLines(before, after), ...functionLines(before, after), ...otherLines(before, after)];
}

/** True when a save gives a role more than it had: a gained operation, a rewritten rule or other token settings. */
export function widens(changes: Change[]): boolean {
  return changes.some((change) => change.gained.length > 0 || change.changed.length > 0 || change.other === true);
}

export function serialize(doc: PermissionsDoc): string {
  return `${JSON.stringify(doc, null, 2)}\n`;
}

/** True when saving the document as the grid writes it would change the text beyond its content: comments, layout, order. */
export function rewriteLosesText(text: string, doc: PermissionsDoc): boolean {
  return text.trim() !== serialize(doc).trim();
}

/** The file name and type an export carries: a JSON text stays JSON, anything else is YAML. A label tells two files apart. */
export function exportName(text: string, label = ''): { filename: string; type: string } {
  const stem = label === '' ? 'permissions' : `permissions-${label}`;
  return text.trimStart().startsWith('{')
    ? { filename: `${stem}.json`, type: 'application/json' }
    : { filename: `${stem}.yaml`, type: 'text/yaml' };
}
