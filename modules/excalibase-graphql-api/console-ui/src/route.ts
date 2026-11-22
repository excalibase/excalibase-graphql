// Hash routes (#/data/public.orders), so the engine never needs to answer a path other than the page itself.

export const PAGES = ['data', 'schema', 'permissions', 'explorer', 'replicas'] as const;
export type Page = (typeof PAGES)[number];
export const PERMISSION_TABS = ['document', 'grid', 'token', 'history', 'validate'] as const;
export type PermissionTab = (typeof PERMISSION_TABS)[number];
export interface Route { page: Page; sub: string | null }

const FIRST: Route = { page: 'data', sub: null };

export function parseHash(hash: string): Route {
  const [page, ...rest] = hash.replace(/^#\/?/, '').split('/');
  const sub = rest.length > 0 ? safeDecode(rest.join('/')) : null;
  if (page === 'permissions') {
    return { page, sub: PERMISSION_TABS.find((tab) => tab === sub) ?? 'document' };
  }
  const known = PAGES.find((name) => name === page);
  if (!known) {
    return FIRST;
  }
  return { page: known, sub: known === 'data' && sub ? sub : null };
}

function safeDecode(part: string): string {
  try {
    return decodeURIComponent(part);
  } catch {
    return part;
  }
}

export function hashFor(route: Route): string {
  return '#/' + route.page + (route.sub ? '/' + encodeURIComponent(route.sub) : '');
}
