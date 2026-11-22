import { createContext, useContext, useMemo, useSyncExternalStore, type ReactNode } from 'react';
import { DocumentStore, type DocState } from './documentStore';

const Context = createContext<DocumentStore | null>(null);

export function DocumentProvider({ store, children }: { readonly store: DocumentStore; readonly children: ReactNode }) {
  return <Context.Provider value={store}>{children}</Context.Provider>;
}

export function useDocument(): { store: DocumentStore; state: DocState } {
  const store = useContext(Context);
  if (!store) {
    throw new Error('useDocument needs a DocumentProvider');
  }
  const state = useSyncExternalStore(store.subscribe, store.snapshot);
  return useMemo(() => ({ store, state }), [store, state]);
}
