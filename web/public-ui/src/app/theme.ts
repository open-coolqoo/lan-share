export type ThemeMode = 'auto' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'lanshare.theme'
// Matches the static meta in index.html; dark mirrors the dark page background.
const THEME_COLOR: Record<ResolvedTheme, string> = {
  light: '#0f6b55',
  dark: '#101915',
}

export function loadThemeMode(storage?: Pick<Storage, 'getItem'>): ThemeMode {
  const source = storage ?? (typeof window === 'undefined' ? undefined : window.localStorage)
  const stored = source?.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' ? stored : 'auto'
}

export function saveThemeMode(mode: ThemeMode, storage?: Pick<Storage, 'setItem'>): void {
  const source = storage ?? (typeof window === 'undefined' ? undefined : window.localStorage)
  source?.setItem(STORAGE_KEY, mode)
}

// The toggle cycles auto → dark → light → auto so a system follower is always
// reachable and neither manual state takes more than two clicks to escape.
export function nextThemeMode(mode: ThemeMode): ThemeMode {
  if (mode === 'auto') return 'dark'
  if (mode === 'dark') return 'light'
  return 'auto'
}

export function resolveTheme(mode: ThemeMode, prefersDark: boolean): ResolvedTheme {
  if (mode === 'auto') return prefersDark ? 'dark' : 'light'
  return mode
}

// Writes the resolved state so the stylesheet only ever sees light or dark;
// returns the resolution for callers that mirror it elsewhere (meta theme-color).
export function applyTheme(mode: ThemeMode, doc?: Document): ResolvedTheme {
  const scope = doc ?? (typeof document === 'undefined' ? undefined : document)
  if (!scope?.documentElement) return 'light'
  const prefersDark = Boolean(scope.defaultView?.matchMedia('(prefers-color-scheme: dark)').matches)
  const resolved = resolveTheme(mode, prefersDark)
  scope.documentElement.dataset.theme = resolved
  scope.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[resolved])
  return resolved
}
