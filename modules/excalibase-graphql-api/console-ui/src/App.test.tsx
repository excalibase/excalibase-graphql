import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { App } from './App';

describe('App', () => {
  test('says what this is and where the working console is', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: 'Excalibase console' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '/console/legacy' }).getAttribute('href')).toBe('/console/legacy');
  });

  test('mounts the editor with a name a person can find it by', () => {
    render(<App />);

    expect(document.querySelector('[aria-label="Permissions document (preview)"]')).not.toBeNull();
  });
});
