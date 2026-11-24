import { useEffect, useState } from 'react';

export type Page = 'data' | 'schema' | 'permissions' | 'explorer' | 'replicas' | 'settings';
export const PAGES: readonly Page[] = ['data', 'schema', 'permissions', 'explorer', 'replicas', 'settings'];

export interface Route {
  page: Page;
  /** The part after the page: a table for Schema, a view for Permissions. Empty when there is none. */
  arg: string;
}

export const DEFAULT_ROUTE: Route = { page: 'data', arg: '' };

/** Parses `#/schema/public.orders`. Anything unknown goes to the first page, never to a blank one. */
export function parseRoute(hash: string): Route {
  const [page = '', ...rest] = hash.replace(/^#\/?/, '').split('/');
  if (!PAGES.includes(page as Page)) {
    return DEFAULT_ROUTE;
  }
  let arg = rest.join('/');
  try {
    arg = decodeURIComponent(arg);
  } catch {
    arg = '';
  }
  return { page: page as Page, arg };
}

export function hrefOf(page: Page, arg = ''): string {
  return `#/${page}${arg ? `/${encodeURIComponent(arg)}` : ''}`;
}

export function navigate(page: Page, arg = ''): void {
  window.location.hash = hrefOf(page, arg);
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
