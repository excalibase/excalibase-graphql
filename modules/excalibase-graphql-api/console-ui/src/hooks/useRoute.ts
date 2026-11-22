import { useEffect, useState } from 'react';
import { hashFor, parseHash, type Route } from '../route';

export function useRoute(): [Route, (route: Route) => void] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));
  useEffect(() => {
    const follow = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', follow);
    return () => window.removeEventListener('hashchange', follow);
  }, []);
  return [route, (next) => { window.location.hash = hashFor(next); }];
}
