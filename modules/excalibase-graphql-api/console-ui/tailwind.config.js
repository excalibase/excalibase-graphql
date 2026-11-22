/** Colors are tokens on :root (src/index.css), so a second theme is one more block of variables. */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--canvas)',
        surface: 'var(--surface)',
        sunken: 'var(--sunken)',
        line: 'var(--line)',
        ink: 'var(--ink)',
        soft: 'var(--ink-soft)',
        accent: 'var(--accent)',
        'accent-strong': 'var(--accent-strong)',
        'accent-wash': 'var(--accent-wash)',
        danger: 'var(--danger)',
        warn: 'var(--warn)',
        ok: 'var(--ok)',
      },
      fontFamily: {
        sans: ['system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
};
