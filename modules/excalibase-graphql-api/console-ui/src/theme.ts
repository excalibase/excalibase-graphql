import { createContext, useCallback, useContext, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark' | 'system';
const KEY = 'excalibase.console.theme';

function stored(): Theme {
  try {
    const value = window.sessionStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}

export function applyTheme(theme: Theme, root: HTMLElement = document.documentElement): void {
  if (theme === 'system') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', theme);
  }
}

/** Light, dark, or follow the system. The choice lasts for the tab; it is never sent anywhere. */
export function useTheme(): { theme: Theme; setTheme: (theme: Theme) => void } {
  const [theme, setThemeState] = useState<Theme>(stored);
  useEffect(() => applyTheme(theme), [theme]);
  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    try {
      if (next === 'system') {
        window.sessionStorage.removeItem(KEY);
      } else {
        window.sessionStorage.setItem(KEY, next);
      }
    } catch {
      // the choice then lasts for this page load only
    }
  }, []);
  return { theme, setTheme };
}

export const ThemeContext = createContext<ReturnType<typeof useTheme>>({ theme: 'system', setTheme: () => undefined });
export const useThemeChoice = () => useContext(ThemeContext);
