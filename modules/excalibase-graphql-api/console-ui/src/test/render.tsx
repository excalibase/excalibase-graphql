import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { fakeEngine, healthy, type Routes } from './engine';

/** Signs in through the page, as a person would, against the fake engine, and lands on the given page. */
export async function openConsole(hash: string, routes: Routes = healthy()) {
  const engine = fakeEngine(routes);
  const user = userEvent.setup();
  window.location.hash = hash;
  render(<App />);
  await user.type(screen.getByLabelText('Admin secret'), 'a-secret-that-is-long-enough-0123456789');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByRole('navigation', { name: 'Console' });
  return { user, engine };
}
