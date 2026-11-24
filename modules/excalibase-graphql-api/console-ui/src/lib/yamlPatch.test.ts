import { parse } from 'yaml';
import { describe, expect, test } from 'vitest';
import { setOperation, withJwt, type PermissionsDoc } from './docmodel';
import { patchYaml, treesEqual } from './yamlPatch';

const DOCUMENT = `# yaml-language-server: $schema=https://example.test/schema.json
# Who may read and write which table.
format: 1
projectId: my-app            # must equal APP_PROJECT_ID

jwt:
  secret: env:JWT_SECRET     # never the secret itself
  algorithms: [HS256]
  claims:
    role: role
    userId: sub

tables:
  - table: public.todos
    role: user
    select:
      filter: {"owner_id": {"_eq": "X-Excalibase-User-Id"}}   # a user sees only their own rows
      columns: "*"
    delete:
      filter: {"owner_id": {"_eq": "X-Excalibase-User-Id"}}
`;
const tree = (text: string): PermissionsDoc => parse(text, { version: '1.1' }) as PermissionsDoc;

describe('patching a YAML document from the grid', () => {
  test('a change nothing makes gives back the very same bytes', () => {
    expect(patchYaml(DOCUMENT, tree(DOCUMENT))).toBe(DOCUMENT);
  });

  test('lines the change does not touch keep their text, apart from the space before a comment', () => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', 'anon', 'select', true);

    const patched = patchYaml(DOCUMENT, next) as string;
    const bare = (line: string) => line.replace(/\s+#.*$/, '').trimEnd();

    expect(patched.split('\n').map(bare).slice(0, DOCUMENT.split('\n').length - 1)).toEqual(DOCUMENT.split('\n').map(bare).slice(0, -1));
  });

  test('a ticked operation is added and every comment stays where it was', () => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', 'user', 'insert', true);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    ['# yaml-language-server', '# Who may read', '# must equal APP_PROJECT_ID', '# never the secret itself', '# a user sees only their own rows']
      .forEach((comment) => expect(patched).toContain(comment));
  });

  test('an unticked operation leaves the others and their comments alone', () => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', 'user', 'delete', false);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched).toContain('# a user sees only their own rows');
    expect(patched).not.toContain('delete:');
  });

  test('a new rule is appended as a block, not as a flow list', () => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', 'anon', 'select', true);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched).toContain('- table: public.todos\n    role: anon');
  });

  test('the first rule of an empty list is a block too', () => {
    const empty = '# nothing yet\nformat: 1\nprojectId: a\ntables: []\n';
    const next = setOperation(tree(empty), 'public.t', 'anon', 'select', true);

    const patched = patchYaml(empty, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched.startsWith('# nothing yet')).toBe(true);
    expect(patched).not.toContain('tables: [');
  });

  test('removing the only rule leaves a valid empty list', () => {
    const one = '# c\nformat: 1\nprojectId: a\ntables:\n  - table: public.t\n    role: anon\n    select:\n      filter: {}\n      columns: "*"\n';
    const next = setOperation(tree(one), 'public.t', 'anon', 'select', false);

    const patched = patchYaml(one, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched).toContain('# c');
  });

  test('the token settings are patched in place and the rest keeps its comments', () => {
    const next = withJwt(tree(DOCUMENT), { ...(tree(DOCUMENT).jwt as object), audience: 'my-api' });

    const patched = patchYaml(DOCUMENT, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched).toContain('# never the secret itself');
    expect(patched).toContain('# a user sees only their own rows');
  });

  test('removing the token section removes just that section', () => {
    const next = withJwt(tree(DOCUMENT), null);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect(treesEqual(tree(patched), next)).toBe(true);
    expect(patched).toContain('# a user sees only their own rows');
  });

  test('a role a YAML 1.1 reader would turn into a boolean is quoted', () => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', 'no', 'select', true);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect((tree(patched).tables as { role: string }[]).map((rule) => rule.role)).toEqual(['user', 'no']);
  });

  test.each(['on', 'off', 'yes', 'y', 'n', 'null'])('the role %s survives a 1.1 reader as text', (role) => {
    const next = setOperation(tree(DOCUMENT), 'public.todos', role, 'select', true);

    const patched = patchYaml(DOCUMENT, next) as string;

    expect((tree(patched).tables as { role: string }[])[1]?.role).toBe(role);
  });

  test('text that is not a YAML map cannot be patched', () => {
    expect(patchYaml('- a\n- b\n', { tables: [] })).toBeNull();
    expect(patchYaml('a: [1\n', { tables: [] })).toBeNull();
    expect(patchYaml('', { tables: [] })).toBeNull();
  });

  test('trees are equal only when types match as well as values', () => {
    expect(treesEqual({ a: 1 }, { a: 1 })).toBe(true);
    expect(treesEqual({ a: 1 }, { a: '1' })).toBe(false);
    expect(treesEqual({ a: [1, 2] }, { a: [2, 1] })).toBe(false);
    expect(treesEqual({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true);
  });
});
