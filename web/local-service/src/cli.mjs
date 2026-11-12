#!/usr/bin/env node
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { LocalService } from './service.mjs'

function argument(name, fallback) {
  const index = process.argv.indexOf(name)
  if (index === -1) return fallback
  return process.argv[index + 1] || fallback
}

const distDir = argument('--dist', fileURLToPath(new URL('../../public-ui/dist/', import.meta.url)))
const hostPort = Number(argument('--port', '0')) || 0
const openBrowser = !process.argv.includes('--no-open')

const service = new LocalService({
  distDir: path.resolve(distDir),
  hostPort,
  log: (message) => console.log(`[lanshare] ${message}`),
})

const info = await service.start()

console.log('')
console.log('LanShare local service is running.')
console.log(`Host page:  ${info.hostUrl}`)
console.log(`Guest URL:  ${info.guestUrl}`)
console.log('')
console.log('Open the host page (or leave it open) while another device pairs.')
console.log('Press Ctrl+C to stop.')

if (openBrowser) {
  const command = process.platform === 'darwin'
    ? 'open'
    : process.platform === 'win32'
      ? 'cmd'
      : 'xdg-open'
  const args = process.platform === 'win32'
    ? ['/c', 'start', '', info.hostUrl]
    : [info.hostUrl]
  try {
    spawn(command, args, { stdio: 'ignore', detached: true }).unref()
  } catch {
    // Opening a browser is a convenience; the printed URL still works.
  }
}

const stop = async () => {
  await service.stop()
  process.exit(0)
}

process.on('SIGINT', stop)
process.on('SIGTERM', stop)
