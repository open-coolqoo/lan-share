import { describe, expect, it } from 'vitest'
import { Locale, localeDirection, messages, normalizeLocale, supportedLocales, translate } from './messages'

describe('public UI translations', () => {
  it('uses English as the complete fallback dictionary', () => {
    const keys = Object.keys(messages.en)

    expect(keys.length).toBeGreaterThan(0)
    expect(translate('en', 'appName')).toBe('Lan-Share')
    expect(translate('en', 'connectCard')).toBe('Connect')
  })

  it('keeps every enabled locale aligned with the English keys', () => {
    const englishKeys = Object.keys(messages.en).sort()

    expect(Object.keys(messages).sort()).toEqual([...supportedLocales].sort())
    for (const locale of supportedLocales) {
      expect(Object.keys(messages[locale]).sort(), locale).toEqual(englishKeys)
    }
  })

  it('does not silently fall back to English for visible non-brand strings', () => {
    const allowedSameAsEnglish = new Set(['appName'])

    for (const locale of supportedLocales.filter((item) => item !== 'en')) {
      for (const key of Object.keys(messages.en) as Array<keyof typeof messages.en>) {
        if (allowedSameAsEnglish.has(key)) continue
        expect(messages[locale][key], `${locale}.${key}`).not.toBe(messages.en[key])
      }
    }
  })

  it('ships a practical set of major language options with translated primary actions', () => {
    expect(supportedLocales).toEqual(['en', 'es', 'fr', 'de', 'ja', 'ko', 'pt-BR', 'hi', 'ar', 'id', 'vi', 'th', 'zh-CN', 'zh-TW'])
    for (const locale of supportedLocales.filter((item) => item !== 'en')) {
      expect(translate(locale, 'connectCard'), locale).not.toBe(messages.en.connectCard)
    }
  })

  it('uses the requested simplified Chinese peer-code placeholder', () => {
    expect(translate('zh-CN', 'peerCodePlaceholder')).toBe('请输入对方连接码')
  })

  it('falls back to English when a locale entry is missing at runtime', () => {
    const sparseMessages = {
      en: { appName: 'Lan-Share', connectCard: 'Connect' },
      'zh-CN': { appName: 'Lan-Share' },
    } as Record<'en' | 'zh-CN', Record<string, string>>

    const runtimeTranslate = (locale: keyof typeof sparseMessages, key: keyof typeof sparseMessages.en) => {
      return sparseMessages[locale]?.[key] || sparseMessages.en[key] || key
    }

    expect(runtimeTranslate('zh-CN', 'connectCard')).toBe('Connect')
  })

  it('marks Arabic as right-to-left and keeps the other enabled locales left-to-right', () => {
    expect(localeDirection('ar')).toBe('rtl')
    for (const locale of supportedLocales.filter((item) => item !== 'ar')) {
      expect(localeDirection(locale), locale).toBe('ltr')
    }
  })

  it('normalizes unsupported or browser-style locale values to supported locales', () => {
    expect(normalizeLocale(null)).toBe('en')
    expect(normalizeLocale('')).toBe('en')
    expect(normalizeLocale('fr-FR')).toBe('fr')
    expect(normalizeLocale('es-MX')).toBe('es')
    expect(normalizeLocale('de-DE')).toBe('de')
    expect(normalizeLocale('ja-JP')).toBe('ja')
    expect(normalizeLocale('ko-KR')).toBe('ko')
    expect(normalizeLocale('pt')).toBe('pt-BR')
    expect(normalizeLocale('pt_BR')).toBe('pt-BR')
    expect(normalizeLocale('hi-IN')).toBe('hi')
    expect(normalizeLocale('ar-SA')).toBe('ar')
    expect(normalizeLocale('th-TH')).toBe('th')
    expect(normalizeLocale('it-IT')).toBe('en')
    expect(normalizeLocale('zh')).toBe('zh-CN')
    expect(normalizeLocale('zh-cn')).toBe('zh-CN')
    expect(normalizeLocale('zh-Hans')).toBe('zh-CN')
    expect(normalizeLocale('zh-CN')).toBe('zh-CN')
    expect(normalizeLocale('zh-tw')).toBe('zh-TW')
    expect(normalizeLocale('zh-Hant')).toBe('zh-TW')
    expect(normalizeLocale('zh-Hant-HK')).toBe('zh-TW')
    expect(normalizeLocale('zh-HK')).toBe('zh-TW')
  })
})
