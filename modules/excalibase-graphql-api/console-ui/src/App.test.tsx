import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from './App';
import { fakeEngine, healthy } from './test/engine';
import { openConsole } from './test/render';

describe('signing in', () => {
  it('shows a plain sign-in form first', () => {
    fakeEngine(healthy());
    render(<App />);

    expect(screen.getByRole('heading', { name: 'Excalibase console' })).toBeVisible();
    expect(screen.getByLabelText('Admin secret')).toHaveAttribute('type', 'password');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
  });

  it('says in plain words that a wrong secret was not accepted, and stays on the form', async () => {
    fakeEngine(healthy({ 'GET /replicas': { status: 401, body: { error: 'unauthorized' } } }));
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Admin secret'), 'wrong-wrong-wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That secret was not accepted.');
    expect(screen.getByLabelText('Admin secret')).toHaveValue('wrong-wrong-wrong');
  });

  it('says the engine could not be reached when nothing answers', async () => {
    fakeEngine({});
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Admin secret'), 'whatever-secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The engine could not be reached.');
  });

  it('sends the secret only in the header, never the address or the body', async () => {
    const { engine } = await openConsole('#/replicas');

    const first = engine.calls[0];
    expect(first?.headers['X-Excalibase-Admin-Secret']).toBe('a-secret-that-is-long-enough-0123456789');
    expect(engine.calls.every((call) => !call.path.includes('secret') && !(call.body ?? '').includes('a-secret'))).toBe(true);
  });

  it('returns to the sign-in form with a notice when the engine ends the session', async () => {
    const { user } = await openConsole('#/replicas');
    expect(await screen.findByRole('heading', { name: 'Replicas' })).toBeVisible();

    fakeEngine(healthy({ 'GET /replicas': { status: 401, body: { error: 'unauthorized' } } }));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your session ended. Sign in again.');
  });

  it('signs out from the side menu', async () => {
    const { user } = await openConsole('#/replicas');

    await user.click(within(screen.getByRole('navigation', { name: 'Console' })).getByRole('button', { name: 'Sign out' }));

    expect(screen.getByLabelText('Admin secret')).toBeVisible();
  });
});

describe('the menu', () => {
  it('lists every page in the order of the design, with the tables below on a data page', async () => {
    await openConsole('#/schema');
    const menu = screen.getByRole('navigation', { name: 'Console' });

    const names = within(menu)
      .getAllByRole('link')
      .slice(0, 6)
      .map((link) => link.textContent);
    expect(names).toEqual(['Data', 'Schema', 'Permissions', 'API explorer', 'Replicas', 'Settings']);
    expect(await within(menu).findByRole('link', { name: 'orders' })).toHaveAttribute('href', '#/schema/public.orders');
    expect(within(menu).getByRole('link', { name: 'Schema' })).toHaveAttribute('aria-current', 'page');
  });

  it('opens and closes on a phone with a button that says whether it is open', async () => {
    const { user } = await openConsole('#/replicas');
    const button = screen.getByRole('button', { name: 'Menu' });

    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
  });

  it('has a link that moves focus to the page without changing the address', async () => {
    const { user } = await openConsole('#/replicas');

    await user.click(screen.getByRole('link', { name: 'Skip to the page' }));

    expect(screen.getByRole('main')).toHaveFocus();
    expect(window.location.hash).toBe('#/replicas');
  });

  it('the pages that are not built yet say so and point to what works', async () => {
    await openConsole('#/explorer');

    expect(await screen.findByRole('heading', { name: 'API explorer' })).toBeVisible();
    expect(screen.getByText(/not ready yet/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'See your tables' })).toHaveAttribute('href', '#/schema');
  });
});

describe('dark mode', () => {
  it('flips the theme from the side menu and keeps it for the tab', async () => {
    const { user } = await openConsole('#/replicas');

    await user.click(screen.getByRole('button', { name: 'Switch to the dark theme' }));

    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(window.sessionStorage.getItem('excalibase.console.theme')).toBe('dark');
    expect(screen.getByRole('button', { name: 'Switch to the light theme' })).toBeVisible();
  });
});
