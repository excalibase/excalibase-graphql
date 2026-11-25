import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { healthy } from '../test/engine';
import { openConsole } from '../test/render';

describe('the schema page', () => {
  it('shows the columns with their keys and where they point', async () => {
    await openConsole('#/schema/public.orders');

    const columns = await screen.findByRole('table');
    expect(within(columns).getByRole('row', { name: /id int8 Primary key/ })).toBeVisible();
    expect(within(columns).getByRole('row', { name: /customer_id int8 → customers/ })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'orders' })).toBeVisible();
  });

  it('describes each relationship in a sentence and links to the other table', async () => {
    await openConsole('#/schema/public.orders');

    const relations = await screen.findAllByTestId('relationship');
    expect(relations).toHaveLength(2);
    expect(relations[0]).toHaveTextContent('Each row in orders points to one row in customers');
    expect(within(relations[0] as HTMLElement).getByRole('link', { name: 'customers' })).toHaveAttribute('href', '#/schema/public.customers');
    expect(relations[1]).toHaveTextContent('Each row in orders can have many rows in order_items');
  });

  it('says which columns a role cannot read', async () => {
    await openConsole('#/schema/public.orders');

    expect(await screen.findByText(/internal_note/, { selector: '.mono' })).toBeVisible();
    expect(await screen.findByText(/is hidden from role/)).toBeVisible();
    expect(screen.getByRole('row', { name: /internal_note text Hidden from user/ })).toBeVisible();
    expect(screen.getByRole('link', { name: 'See permissions' })).toHaveAttribute('href', '#/permissions/%40user');
  });

  it('says a role cannot read a table at all when it has no read rule', async () => {
    const { user } = await openConsole('#/schema/public.customers');

    await user.selectOptions(await screen.findByLabelText('Show what this role can read'), 'user');

    expect(await screen.findByText(/cannot read/)).toBeVisible();
  });

  it('opens the first table when none is named', async () => {
    await openConsole('#/schema');

    expect(await screen.findByRole('heading', { name: 'orders' })).toBeVisible();
  });

  it('says so when the table is not there', async () => {
    await openConsole('#/schema/public.ghost');

    expect(await screen.findByText('That table is not here')).toBeVisible();
  });

  it('says so when there are no tables', async () => {
    await openConsole('#/schema', healthy({ 'GET /schema': { body: { tables: [], functions: [] } } }));

    expect(await screen.findByText('No tables to show')).toBeVisible();
  });

  it('says what went wrong and offers to try again when the schema cannot be read', async () => {
    await openConsole('#/schema', healthy({ 'GET /schema': { status: 503, body: { error: 'schema_unavailable' } } }));

    expect(await screen.findByText(/schema is not loaded yet/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});
