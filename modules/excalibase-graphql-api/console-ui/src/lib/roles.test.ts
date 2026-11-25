import { describe, expect, it } from 'vitest';
import { hiddenColumns, listRoles, tableCount } from './roles';

const doc = {
  tables: [
    { table: 'public.orders', role: 'user', select: { filter: {}, columns: ['id'] } },
    { table: 'public.items', role: 'user', select: { filter: {}, columns: '*' } },
    { table: 'public.items', role: 'anon', insert: { check: {}, columns: '*' } },
  ],
};

describe('listRoles', () => {
  it('lists the named roles and the added ones, sorted, or anon and user when there are none', () => {
    expect(listRoles(doc, ['editor'])).toEqual(['anon', 'editor', 'user']);
    expect(listRoles(null, [])).toEqual(['anon', 'user']);
  });
});

describe('tableCount', () => {
  it('counts the tables a role has any rule on', () => {
    expect(tableCount(doc, 'user')).toBe(2);
    expect(tableCount(doc, 'nobody')).toBe(0);
  });
});

describe('hiddenColumns', () => {
  const columns = ['id', 'note'];
  it('names the columns a role cannot read', () => {
    expect(hiddenColumns(doc, 'public.orders', 'user', columns)).toEqual(['note']);
  });
  it('says nothing is hidden when every column is readable', () => {
    expect(hiddenColumns(doc, 'public.items', 'user', columns)).toEqual([]);
  });
  it('says the whole table is hidden when the role cannot read it', () => {
    expect(hiddenColumns(doc, 'public.items', 'anon', columns)).toBe('all');
    expect(hiddenColumns(doc, 'public.orders', 'anon', columns)).toBe('all');
  });
  it('does not guess at a rule it cannot read', () => {
    const odd = { tables: [{ table: 't', role: 'r', select: { columns: { except: ['a'] } } }] };
    expect(hiddenColumns(odd, 't', 'r', ['a'])).toBeNull();
  });
});
