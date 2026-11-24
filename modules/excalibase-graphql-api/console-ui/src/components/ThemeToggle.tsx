import { useThemeChoice, type Theme } from '../theme';

const CHOICES: Array<{ value: Theme; label: string }> = [
  { value: 'system', label: 'Match my device' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

export function ThemeChoice() {
  const { theme, setTheme } = useThemeChoice();
  return (
    <fieldset className="field">
      <legend className="label">Appearance</legend>
      <div className="row">
        {CHOICES.map((choice) => (
          <label key={choice.value} className="btn">
            <input type="radio" name="theme" value={choice.value} checked={theme === choice.value} onChange={() => setTheme(choice.value)} />
            {choice.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** One button in the side column: light and dark flip, and the choice is kept for the tab. */
export function ThemeButton() {
  const { theme, setTheme } = useThemeChoice();
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
  return (
    <button type="button" className="nav" onClick={() => setTheme(dark ? 'light' : 'dark')} aria-label={dark ? 'Switch to the light theme' : 'Switch to the dark theme'}>
      <span>{dark ? 'Light theme' : 'Dark theme'}</span>
    </button>
  );
}
