// The room/signaling service is not part of this public repository. These
// placeholder paths must be replaced by the private deployment through the
// build environment; they deliberately do not match the production interface.
const configuredBase = (import.meta.env.VITE_PRIVATE_API_BASE || '').trim().replace(/\/+$/, '')
export const privateApiBase = configuredBase || '/__private__'

export const roomEndpoints = {
  create: `${privateApiBase}/rooms/create`,
  join: `${privateApiBase}/rooms/join`,
  status: (roomId: string) => `${privateApiBase}/rooms/${encodeURIComponent(roomId)}/status`,
  heartbeat: (roomId: string) => `${privateApiBase}/rooms/${encodeURIComponent(roomId)}/heartbeat`,
  close: (roomId: string) => `${privateApiBase}/rooms/${encodeURIComponent(roomId)}/close`,
}

export function signalingEndpoint(): string {
  const configured = import.meta.env.VITE_SIGNALING_URL
  if (configured) return configured
  if (privateApiBase.startsWith('http://') || privateApiBase.startsWith('https://')) {
    const base = new URL(privateApiBase)
    const protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${base.host}${base.pathname.replace(/\/+$/, '')}/signaling`
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${privateApiBase}/signaling`
}
