const encoder = new TextEncoder()
const appSalt = 'lanshare-room-v1:2026-06-25'

export async function deriveRoomKey(code: string): Promise<string> {
  if (!crypto.subtle) {
    throw new Error('Secure cryptography is unavailable. Open Lan-Share over HTTPS or localhost before connecting devices.')
  }
  const baseKey = await crypto.subtle.importKey('raw', encoder.encode(code), 'HKDF', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: encoder.encode(appSalt),
      info: encoder.encode('lanshare-room-v1'),
    },
    baseKey,
    256,
  )
  const digest = await crypto.subtle.digest('SHA-256', bits)
  return base64url(new Uint8Array(digest))
}

function base64url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}
