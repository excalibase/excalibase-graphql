// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';

const SRC = new URL('.', import.meta.url).pathname;

function sources(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      return sources(path);
    }
    return /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

// What the old console's ConsoleSourceTest guarded, for the code written here: nothing that turns text into markup
// or code, nothing that keeps the admin secret where a script of another page could read it, nothing from outside.
const BANNED = [
  'localStorage',
  'document.cookie',
  'dangerouslySetInnerHTML',
  'innerHTML',
  'outerHTML',
  'insertAdjacentHTML',
  'document.write',
  'createContextualFragment',
  'srcdoc',
  'DOMParser',
  'eval(',
  'new Function',
  'Function(',
  'setTimeout("',
  "setTimeout('",
  'javascript:',
  'XMLHttpRequest',
  'import(',
  'http://',
  'https://',
  "setAttribute('style'",
  'setAttribute("style"',
];

describe('the console source', () => {
  test('has files to check', () => {
    expect(sources(SRC).length).toBeGreaterThan(3);
  });

  test.each(sources(SRC).map((path) => [path.slice(SRC.length), path]))('%s uses none of the banned APIs', (_name, path) => {
    const text = readFileSync(path as string, 'utf8');

    BANNED.forEach((token) => expect(text, token).not.toContain(token));
  });

  test('sessionStorage appears nowhere yet: the secret has no place to rest until adminApi owns it', () => {
    sources(SRC).forEach((path) => expect(readFileSync(path, 'utf8')).not.toContain('sessionStorage'));
  });
});
