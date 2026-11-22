import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import type { Plugin } from 'vite';

const COMPRESSIBLE = /\.(js|css|svg)$/;

function files(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

/** Writes a .gz beside every script, stylesheet and svg the build made; the engine serves it to clients that ask. */
export function gzipAssets(outDir: string): Plugin {
  return {
    name: 'excalibase-gzip-assets',
    apply: 'build',
    closeBundle() {
      files(outDir)
        .filter((path) => COMPRESSIBLE.test(path))
        .forEach((path) => writeFileSync(`${path}.gz`, gzipSync(readFileSync(path), { level: 9 })));
    },
  };
}
