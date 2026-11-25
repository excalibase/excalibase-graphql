import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import userEvent from '@testing-library/user-event';
import { fakeEngine, healthy, META } from '../test/engine';
import { openConsole } from '../test/render';

describe('the replicas page', () => {
  it('lists the replica with its state', async () => {
    await openConsole('#/replicas');

    expect(await screen.findByRole('cell', { name: 'This replica' })).toBeVisible();
    expect(screen.getByText('Ready')).toBeVisible();
  });

  it('says so when no replica is reported', async () => {
    await openConsole('#/replicas', healthy({ 'GET /replicas': { body: { replicas: [] } } }));

    await screen.findByText('No replicas reported');
  });

  it('says what failed and offers a retry', async () => {
    await openConsole('#/replicas', healthy({ 'GET /replicas': { body: { replicas: [{ self: true, version: 3, ready: true, source: 'file' }] } } }));
    await screen.findByRole('cell', { name: 'This replica' });
    fakeEngine(healthy({ 'GET /replicas': { status: 500, body: {} } }));

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(await screen.findByText('The request failed (error_500).')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});

describe('the settings page', () => {
  it('shows where the permissions come from, in plain words', async () => {
    await openConsole('#/settings');

    expect(await screen.findByText('Permissions come from')).toBeVisible();
    expect(screen.getByText('Yes')).toBeVisible();
  });

  it('says a read-only source is read-only', async () => {
    await openConsole('#/settings', healthy({ 'GET /metadata': { body: { ...META, writable: false } } }));

    expect(await screen.findByText('No, read-only')).toBeVisible();
  });

  it('shows the last text the engine refused', async () => {
    await openConsole('#/settings', healthy({ 'GET /metadata': { body: { ...META, lastRefusal: 'bad role', lastRefusalAt: '2026-11-20T10:00:00Z' } } }));

    expect(await screen.findByText('bad role (2026-11-20 10:00:00 UTC)')).toBeVisible();
  });

  it('checks a pasted text without saving it', async () => {
    const { user, engine } = await openConsole('#/settings', healthy({ 'POST /validate': { body: { valid: false, errors: ['tables: must be a list'] } } }));

    await user.type(await screen.findByLabelText('Text to check'), 'tables: 1');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByText('tables: must be a list')).toBeVisible();
    expect(engine.calls.some((call) => call.method === 'PUT')).toBe(false);
  });
});
