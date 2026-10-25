import { describe, expect, it } from 'vitest'
import { applyTheme, loadThemeMode, nextThemeMode, resolveTheme } from './theme'

function fakeStorage(items: Record<string, string> = {}) {
  return {
    getItem: (key: string) => items[key] ?? null,
    setItem: (key: string, value: string) => {
      items[key] = value
    },
  }
}

describe('theme mode', () => {
  it('defaults to auto when nothing was stored', () => {
    expect(loadThemeMode(fakeStorage())).toBe('auto')
    expect(loadThemeMode()).toBe('auto')
  })

  it('keeps stored manual modes and rejects unknown values', () => {
    expect(loadThemeMode(fakeStorage({ 'lanshare.theme': 'dark' }))).toBe('dark')
    expect(loadThemeMode(fakeStorage({ 'lanshare.theme': 'light' }))).toBe('light')
    expect(loadThemeMode(fakeStorage({ 'lanshare.theme': 'blue' }))).toBe('auto')
  })

  it('cycles auto → dark → light → auto', () => {
    expect(nextThemeMode('auto')).toBe('dark')
    expect(nextThemeMode('dark')).toBe('light')
    expect(nextThemeMode('light')).toBe('auto')
  })

  it('resolves auto against the system preference and passes manual modes through', () => {
    expect(resolveTheme('auto', true)).toBe('dark')
    expect(resolveTheme('auto', false)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
    expect(resolveTheme('light', true)).toBe('light')
  })

  it('applies the resolved theme to the document and mirrors it in the theme-color meta', () => {
    const element = () => ({ dataset: {} as Record<string, string>, attributes: {} as Record<string, string>, setAttribute(name: string, value: string) { this.attributes[name] = value } })
    const meta = element()
    const documentElement = element()
    const doc = {
      documentElement,
      defaultView: { matchMedia: (query: string) => ({ matches: query.includes('dark') }) },
      querySelector: (selector: string) => (selector === 'meta[name="theme-color"]' ? meta : null),
    } as unknown as Document

    expect(applyTheme('auto', doc)).toBe('dark')
    expect(documentElement.dataset['theme']).toBe('dark')
    expect(meta.attributes['content']).toBe('#101915')

    expect(applyTheme('light', doc)).toBe('light')
    expect(documentElement.dataset['theme']).toBe('light')
    expect(meta.attributes['content']).toBe('#0f6b55')
  })

  it('survives a missing document instead of throwing during a node render', () => {
    expect(applyTheme('dark', undefined)).toBe('light')
  })
})
