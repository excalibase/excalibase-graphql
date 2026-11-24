import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { gzipAssets } from './vite.gzip.ts';

const OUT_DIR = 'dist';

// The engine serves the build under /console, so every asset URL starts there. No sourcemaps and nothing inlined:
// the page the engine serves may not hold a script or a data: URL the policy would have to allow.
export default defineConfig({
  base: '/console/',
  plugins: [react(), gzipAssets(OUT_DIR)],
  build: {
    outDir: OUT_DIR,
    emptyOutDir: true,
    manifest: true,
    sourcemap: false,
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    // the editor is most of the size and the page needs it; a console served from the engine's own disk is not a download
    chunkSizeWarningLimit: 1000,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
