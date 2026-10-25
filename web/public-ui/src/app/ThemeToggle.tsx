import { useEffect, useState } from 'react'
import { Monitor, Moon, Sun } from 'lucide-react'
import { Locale, translate } from '../i18n/messages'
import { applyTheme, loadThemeMode, nextThemeMode, saveThemeMode, ThemeMode } from './theme'

const modeIcons: Record<ThemeMode, typeof Sun> = {
  auto: Monitor,
  dark: Moon,
  light: Sun,
}

const modeLabelKeys: Record<ThemeMode, Parameters<typeof translate>[1]> = {
  auto: 'themeAuto',
  dark: 'themeDark',
  light: 'themeLight',
}

export function ThemeToggle({ locale }: { locale: Locale }) {
  const [mode, setMode] = useState<ThemeMode>(() => loadThemeMode())

  useEffect(() => {
    applyTheme(mode)
    if (mode !== 'auto') return
    // While following the system, an OS-level switch must re-resolve live.
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onSystemChange = () => applyTheme('auto')
    media.addEventListener('change', onSystemChange)
    return () => media.removeEventListener('change', onSystemChange)
  }, [mode])

  function cycle() {
    setMode((current) => {
      const next = nextThemeMode(current)
      saveThemeMode(next)
      return next
    })
  }

  const Icon = modeIcons[mode]
  return (
    <button className="theme-toggle" type="button" aria-label={translate(locale, modeLabelKeys[mode])} title={translate(locale, modeLabelKeys[mode])} onClick={cycle}>
      <Icon size={18} aria-hidden="true" />
      <span className="visually-hidden">{translate(locale, modeLabelKeys[mode])}</span>
    </button>
  )
}
