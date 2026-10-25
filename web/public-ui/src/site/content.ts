export interface FooterLink {
  label: string
  url: string
}

export interface FooterConfig {
  legalName: string
  developerName: string
  contactEmail: string
  copyright: string
  links: FooterLink[]
}

export interface RTCConfig {
  iceServers: RTCIceServer[]
}

export interface PublicSiteConfig {
  siteName: string
  iconUrl: string
  primaryColor: string
  accentColor: string
  defaultLocale: string
  footer: FooterConfig
  rtc: RTCConfig
}

// Branding and footer copy for the self-contained transfer client. The public
// marketing pages live on lan-share.com, so footer links point there.
export const siteConfig: PublicSiteConfig = {
  siteName: 'Lan-Share',
  iconUrl: '/assets/lanshare-icon.svg',
  primaryColor: '#0f6b55',
  accentColor: '#315d8c',
  defaultLocale: 'en',
  footer: {
    legalName: 'Lan-Share',
    developerName: 'Lan-Share Team',
    contactEmail: '',
    copyright: '© 2026 Lan-Share. All rights reserved.',
    links: [
      { label: 'Privacy', url: 'https://lan-share.com/privacy' },
      { label: 'Terms', url: 'https://lan-share.com/terms' },
      { label: 'Security', url: 'https://lan-share.com/security' },
      { label: 'FAQ', url: 'https://lan-share.com/faq' },
      { label: 'Compatibility', url: 'https://lan-share.com/compatibility' },
      { label: 'About', url: 'https://lan-share.com/about' },
    ],
  },
  rtc: {
    iceServers: [],
  },
}
