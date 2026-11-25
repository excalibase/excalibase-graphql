import { createContext, useCallback, useContext, useEffect, useRef, useState, type DependencyList } from 'react';
import { ApiError } from './api';
import { describe, UNREACHABLE } from './lib/outcome';

/** What a page can do when the engine says the session is over. */
export const SessionContext = createContext<{ expired: () => void; signOut: () => void }>({
  expired: () => undefined,
  signOut: () => undefined,
});
export const useSession = () => useContext(SessionContext);

export interface Request<T> {
  status: 'loading' | 'ready' | 'error';
  data: T | null;
  error: string | null;
  loadedAt: Date | null;
  reload: () => void;
}

/** A read of the admin API with its loading and error states; a ended session sends the person back to sign in. */
export function useRequest<T>(load: () => Promise<T>, deps: DependencyList): Request<T> {
  const { expired } = useSession();
  const [stamp, setStamp] = useState(0);
  const [state, setState] = useState<Omit<Request<T>, 'reload'>>({ status: 'loading', data: null, error: null, loadedAt: null });
  const loader = useRef(load);
  loader.current = load;

  useEffect(() => {
    let current = true;
    setState((before) => ({ ...before, status: 'loading', error: null }));
    loader
      .current()
      .then((data) => current && setState({ status: 'ready', data, error: null, loadedAt: new Date() }))
      .catch((failure: unknown) => {
        if (!current) {
          return;
        }
        if (failure instanceof ApiError && failure.status === 401) {
          expired();
          return;
        }
        setState((before) => ({ ...before, status: 'error', error: failure instanceof ApiError ? describe(failure) : UNREACHABLE }));
      });
    return () => {
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, stamp, expired]);

  const reload = useCallback(() => setStamp((count) => count + 1), []);
  return { ...state, reload };
}
