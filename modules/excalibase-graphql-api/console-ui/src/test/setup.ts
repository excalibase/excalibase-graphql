import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { signOut } from '../api';

// The console's source checks run in plain Node; only the page tests have a window.
const hasPage = typeof window !== 'undefined';

afterEach(() => {
  if (hasPage) {
    cleanup();
    signOut();
    window.sessionStorage.clear();
    window.location.hash = '';
    document.documentElement.removeAttribute('data-theme');
  }
});

// jsdom has no layout and no media queries; the console only asks whether the device prefers dark.
if (hasPage && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({ matches: false, media: query, addEventListener: () => undefined, removeEventListener: () => undefined }) as unknown as MediaQueryList;
}
