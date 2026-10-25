import { supportedLocales, type Locale } from './messages'

// Locale-prefixed paths are the canonical, crawlable form (/zh-CN, /zh-CN/faq).
// The server renders them, sends hreflang alternates to them, and 301-redirects
// the legacy ?locale= form, so client-side URL updates must use the same shape.

export function localeFromPath(pathname: string): Locale | null {
  const trimmed = pathname.replace(/^\/+/, '')
  if (!trimmed) return null
  const [segment] = trimmed.split('/')
  if (!segment) return null
  const lower = segment.toLowerCase()
  return supportedLocales.find((locale) => locale.toLowerCase() === lower) ?? null
}

export function stripLocalePath(pathname: string): string {
  const trimmed = pathname.replace(/^\/+/, '')
  if (!trimmed) return '/'
  const segments = trimmed.split('/')
  const rest = localeFromPath(pathname) ? segments.slice(1).join('/') : trimmed
  if (!rest) return '/'
  return `/${rest}`.replace(/\/+$/, '') || '/'
}

export function prefixedPath(pathname: string, locale: Locale | string): string {
  const base = stripLocalePath(pathname)
  if (locale === 'en') return base
  return base === '/' ? `/${locale}` : `/${locale}${base}`
}
