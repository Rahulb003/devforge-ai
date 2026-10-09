import { create } from 'zustand';

type Theme = 'light' | 'dark';

interface ThemeState {
  theme: Theme;
  toggleTheme: () => void;
}

const STORAGE_KEY = 'devforge-theme';

/** Storage can be blocked or throw (private windows, strict settings); the theme then just resets. */
function stored(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

/**
 * Applies the theme to the whole document, so the signed-out pages follow it too.
 *
 * The toggle used to set a "dark" class on the app layout's wrapper, which nothing styled: the
 * button changed its own label and nothing else. The light theme is now real - see the
 * ":root.light" block in index.css.
 */
function apply(theme: Theme) {
  document.documentElement.classList.toggle('light', theme === 'light');
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Not remembered across visits; still applied for this one.
  }
}

const initial = stored();
apply(initial);

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: initial,
  toggleTheme: () => {
    const next = get().theme === 'dark' ? 'light' : 'dark';
    apply(next);
    set({ theme: next });
  },
}));
