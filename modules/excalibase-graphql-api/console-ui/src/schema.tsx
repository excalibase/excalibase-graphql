import { createContext, useContext, type ReactNode } from 'react';
import { getSchema, type SchemaTable, type SchemaView } from './api';
import { useRequest, type Request } from './session';

const Context = createContext<Request<SchemaView> | null>(null);

/** The tables the database holds, read once for the sidebar, the Schema page and the permissions grid. */
export function SchemaProvider({ children }: { children: ReactNode }) {
  const request = useRequest(getSchema, []);
  return <Context.Provider value={request}>{children}</Context.Provider>;
}

export function useSchema(): Request<SchemaView> {
  const value = useContext(Context);
  if (!value) {
    throw new Error('useSchema needs a SchemaProvider above it');
  }
  return value;
}

export const keyOf = (table: Pick<SchemaTable, 'schema' | 'name'>): string => `${table.schema}.${table.name}`;
