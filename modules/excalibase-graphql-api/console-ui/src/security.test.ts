// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';

const SRC = new URL('.', import.meta.url).pathname;

function sources(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      // the test helpers run only under Vitest and are never part of the build
      return name === 'test' ? [] : sources(path);
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

  test('sessionStorage appears in the two files that own a tab-long setting: the admin secret (api) and the theme', () => {
    const holders = sources(SRC)
      .filter((path) => readFileSync(path, 'utf8').includes('sessionStorage'))
      .map((path) => path.slice(SRC.length));

    expect(holders.sort()).toEqual(['api.ts', 'theme.ts']);
  });

  test('the secret is read and written only by api.ts', () => {
    sources(SRC)
      .filter((path) => !path.endsWith('/api.ts'))
      .forEach((path) => expect(readFileSync(path, 'utf8')).not.toContain('X-Excalibase-Admin-Secret'));
  });
});
