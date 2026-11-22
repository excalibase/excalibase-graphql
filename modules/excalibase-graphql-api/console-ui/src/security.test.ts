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

  test('a dynamic import names a file of this build by a relative string, never anything computed', () => {
    sources(SRC).forEach((path) => {
      const text = readFileSync(path, 'utf8');
      const all = text.match(/import\(/g)?.length ?? 0;
      const relative = text.match(/import\('\.\.?\/[A-Za-z0-9_/.-]+'\)/g)?.length ?? 0;
      expect(all, path).toBe(relative);
    });
  });

  test('sessionStorage is touched by one file only: the module that owns the admin secret', () => {
    const users = sources(SRC).filter((path) => readFileSync(path, 'utf8').includes('sessionStorage')).map((path) => path.slice(SRC.length));

    expect(users).toEqual([join('api', 'adminApi.ts')]);
  });

  test('the admin secret header is named in one file only', () => {
    const users = sources(SRC).filter((path) => !path.includes(join('src', 'test')) && readFileSync(path, 'utf8').includes('X-Excalibase-Admin-Secret')).map((path) => path.slice(SRC.length));

    expect(users).toEqual([join('api', 'adminApi.ts')]);
  });
});
