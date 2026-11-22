/** The time as the engine saved it, in UTC, so two people read the same list. */
export function shortTime(iso: string): string {
  return String(iso).replace('T', ' ').slice(0, 19) + ' UTC';
}
