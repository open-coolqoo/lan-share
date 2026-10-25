import type { Locale } from '../i18n/messages'

const socialImagePath = '/assets/og.png'
const socialImageWidth = '1200'
const socialImageHeight = '630'
const socialDescriptionMaxLength = 110
const homepageTitleSuffix = 'Free file transfer between any devices'
const homepageDescription =
  'Free browser file transfer for phone to phone, computer to computer, and phone to computer. Pair with an 8-digit code or QR code—no app install.'
const homepageSocialDescription =
  'Free browser file transfer for phone to phone, PC to PC, and phone to PC. Pair with code or QR—no app.'
const homepageKeywords = [
  'free file transfer',
  'phone to phone file transfer',
  'computer to computer file transfer',
  'phone to computer file transfer',
  'computer to phone file transfer',
  'browser file sharing',
  'local network file sharing',
  'peer-to-peer file transfer',
  'QR code pairing',
  'no app install file transfer',
  'same Wi-Fi file transfer',
]

export function applyMetadata(siteName = 'Lan-Share', description = homepageDescription, locale: Locale = 'en'): void {
  const title = `${siteName} - ${homepageTitleSuffix}`
  const canonical = canonicalURL('/')
  const metaDescription = limitDescription(description || homepageDescription, 160)
  document.title = title
  setMeta('description', metaDescription)
  setMeta('robots', 'index,follow')
  setCanonical(canonical)
  setOpenGraph(title, metaDescription, canonical, 'website', siteName)
  setStructuredData(homepageStructuredData(siteName, metaDescription, canonical, locale))
}

// homepageStructuredData describes the site, the web app, and the publishing
// organization in one @graph, so search and AI answer engines can attribute the
// tool to a real entity instead of an anonymous page.
function homepageStructuredData(siteName: string, description: string, canonical: string, locale: Locale): Record<string, unknown> {
  const origin = new URL(canonical).origin
  const home = `${origin}/`
  const logo = canonicalURL(socialImagePath)
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebSite',
        '@id': `${home}#website`,
        name: siteName,
        url: home,
        inLanguage: locale,
      },
      {
        '@type': 'Organization',
        '@id': `${home}#organization`,
        name: siteName,
        url: home,
        logo,
      },
      {
        '@type': 'WebApplication',
        name: siteName,
        description,
        url: canonical,
        applicationCategory: 'UtilitiesApplication',
        operatingSystem: 'Any modern browser',
        keywords: homepageKeywords.join(', '),
        inLanguage: locale,
        publisher: { '@id': `${home}#organization` },
        isPartOf: { '@id': `${home}#website` },
        featureList: [
          'Free browser file transfer',
          'Phone to phone file transfer',
          'Computer to computer file transfer',
          'Phone to computer file transfer',
          'Computer to phone file transfer',
          'Browser-to-browser file sharing',
          'Short-lived 8-digit code pairing',
          'QR code pairing',
          'Local network direct transfer',
          'Peer-to-peer WebRTC transfer',
          'No app install required',
        ],
        about: homepageKeywords.map((name) => ({
          '@type': 'Thing',
          name,
        })),
        offers: {
          '@type': 'Offer',
          price: '0',
          priceCurrency: 'USD',
        },
      },
    ],
  }
}

function setMeta(name: string, content: string): void {
  let el = document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.name = name
    document.head.appendChild(el)
  }
  el.content = content
}

function setPropertyMeta(property: string, content: string): void {
  let el = document.querySelector<HTMLMetaElement>(`meta[property="${property}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute('property', property)
    document.head.appendChild(el)
  }
  el.content = content
}

function setCanonical(href: string): void {
  let el = document.querySelector<HTMLLinkElement>('link[rel="canonical"]')
  if (!el) {
    el = document.createElement('link')
    el.rel = 'canonical'
    document.head.appendChild(el)
  }
  el.href = href
}

function setOpenGraph(title: string, description: string, canonical: string, type: 'website' | 'article', siteName: string): void {
  const imageURL = canonicalURL(socialImagePath)
  const socialDescription = socialShareDescription(description)
  setPropertyMeta('og:title', title)
  setPropertyMeta('og:description', socialDescription)
  setPropertyMeta('og:site_name', siteName)
  setPropertyMeta('og:url', canonical)
  setPropertyMeta('og:type', type)
  setPropertyMeta('og:image', imageURL)
  setPropertyMeta('og:image:width', socialImageWidth)
  setPropertyMeta('og:image:height', socialImageHeight)
  setMeta('twitter:card', 'summary_large_image')
  setMeta('twitter:title', title)
  setMeta('twitter:description', socialDescription)
  setMeta('twitter:image', imageURL)
}

function socialShareDescription(description: string): string {
  const normalized = description.replace(/\s+/g, ' ').trim()
  if (!normalized || normalized === homepageDescription) {
    return homepageSocialDescription
  }
  return limitDescription(normalized, socialDescriptionMaxLength)
}

function limitDescription(value: string, maxLength: number): string {
  const normalized = value.replace(/\s+/g, ' ').trim()
  const chars = Array.from(normalized)
  if (chars.length <= maxLength) return normalized
  return `${chars.slice(0, Math.max(0, maxLength - 1)).join('')}…`
}

function setStructuredData(value: Record<string, unknown>): void {
  let el = document.querySelector<HTMLScriptElement>('script[type="application/ld+json"][data-lanshare-seo="true"]')
  if (!el) {
    el = document.createElement('script')
    el.type = 'application/ld+json'
    el.dataset.lanshareSeo = 'true'
    document.head.appendChild(el)
  }
  el.textContent = JSON.stringify(value)
}

function canonicalURL(pathname: string): string {
  const url = new URL(pathname || '/', window.location.origin)
  url.search = ''
  url.hash = ''
  return url.toString()
}
