import { describe, expect, test } from 'vitest';
import { allows, isCustom, rewriteLosesText, roleProblem, columnRoles, serialize, setOperation, tableKeysOf, withJwt } from './docmodel';

const base = { format: 1, projectId: 'app', tables: [] as unknown[] };

describe('the grid model', () => {
  test('ticking an operation adds the plain rule and never changes the tree it was given', () => {
    const frozen = Object.freeze({ ...base, tables: Object.freeze([]) });

    const next = setOperation(frozen, 'public.todos', 'anon', 'select', true);

    expect(allows(next, 'public.todos', 'anon', 'select')).toBe(true);
    expect(next.tables).toEqual([{ table: 'public.todos', role: 'anon', select: { filter: {}, columns: '*' } }]);
    expect(frozen.tables).toEqual([]);
  });

  test('unticking the last operation removes the rule', () => {
    const on = setOperation(base, 'public.todos', 'anon', 'select', true);

    expect(setOperation(on, 'public.todos', 'anon', 'select', false).tables).toEqual([]);
  });

  test('a hand-written rule is custom and survives a tick of another operation', () => {
    const tree = { ...base, tables: [{ table: 'public.todos', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] };

    expect(isCustom(tree, 'public.todos', 'user', 'select')).toBe(true);
    const next = setOperation(tree, 'public.todos', 'user', 'delete', true);
    expect((next.tables as { select: unknown }[])[0]?.select).toEqual({ filter: { id: { _eq: 1 } }, columns: '*' });
  });

  test('a role is a lowercase word that is not service, and not a column already', () => {
    expect(roleProblem('editor', ['anon'])).toBeNull();
    expect(roleProblem('Editor', [])).not.toBeNull();
    expect(roleProblem('service', [])).not.toBeNull();
    expect(roleProblem('anon', ['anon'])).toBe('That role is already a column.');
  });

  test('columns are the named roles, or anon and user when there are none', () => {
    expect(columnRoles(base, [])).toEqual(['anon', 'user']);
    expect(columnRoles(setOperation(base, 't.a', 'editor', 'select', true), ['viewer'])).toEqual(['editor', 'viewer']);
  });

  test('a table a rule names that the schema lacks is still listed', () => {
    const tree = setOperation(base, 'public.gone', 'anon', 'select', true);

    expect(tableKeysOf([{ schema: 'public', name: 'todos' }], tree)).toEqual(['public.gone', 'public.todos']);
  });

  test('the grid serialization is JSON with a final newline, and text in another shape counts as a loss', () => {
    expect(serialize(base)).toBe(JSON.stringify(base, null, 2) + '\n');
    expect(rewriteLosesText(serialize(base), base)).toBe(false);
    expect(rewriteLosesText('# note\nformat: 1\n', base)).toBe(true);
  });

  test('withJwt sets and clears the section without touching the rest', () => {
    const set = withJwt(base, { audience: 'a' });
    expect(set.jwt).toEqual({ audience: 'a' });
    expect('jwt' in withJwt(set, null)).toBe(false);
  });
});
