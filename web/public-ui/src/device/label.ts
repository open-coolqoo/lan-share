export type DeviceType = 'pc' | 'mobile' | 'unknown'

export interface DeviceInfo {
  id: string
  label: string
  type: DeviceType
}

export function getLocalDevice(): DeviceInfo {
  const userAgent = navigator.userAgent
  const type: DeviceType = /Mobile|Android|iPhone|iPad/i.test(userAgent) ? 'mobile' : 'pc'
  const browser = detectBrowser(userAgent)
  const label = `${type === 'mobile' ? 'Mobile' : 'PC'} ${browser}`
  return {
    id: getDeviceId(),
    label,
    type,
  }
}

function detectBrowser(userAgent: string): string {
  if (/Edg\//.test(userAgent)) return 'Edge'
  if (/Chrome\//.test(userAgent) && !/Edg\//.test(userAgent)) return 'Chrome'
  if (/Safari\//.test(userAgent) && !/Chrome\//.test(userAgent)) return 'Safari'
  if (/Firefox\//.test(userAgent)) return 'Firefox'
  return 'Browser'
}

function getDeviceId(): string {
  const key = 'lanshare.deviceId'
  const existing = localStorage.getItem(key)
  if (existing) return existing
  const bytes = new Uint8Array(12)
  crypto.getRandomValues(bytes)
  const id = `dev_${Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')}`
  localStorage.setItem(key, id)
  return id
}

