import { useEffect, useId, useMemo, useReducer, useRef, useState } from 'react'
import { Check, ChevronDown, Copy, Download, FolderOpen, Globe2, Image as ImageIcon, Info, LoaderCircle, Plus, RefreshCw, Smile, Undo2, X } from 'lucide-react'
import QRCode from 'qrcode'
import { getLocalDevice } from '../device/label'
import { localeDirection, localeLabels, Locale, normalizeLocale, supportedLocales, translate } from '../i18n/messages'
import { localeFromPath, prefixedPath, stripLocalePath } from '../i18n/paths'
import { isUsableCode, normalizeCode } from '../pairing/code'
import { deriveRoomKey } from '../pairing/roomKey'
import { ApiError, closeRoom, closeRoomBeacon, CloseRoomRequest, createRoom, getRoomStatus, joinRoom, openSignaling, sendRoomHeartbeat, SignalingMessage, SignalingSession } from '../signaling/api'
import { FileOffer, isTextTooLarge } from '../transfer/protocol'
import { ReceivedFile, TextExchange, TransferFileItem, TransferLimits, TransferProgress, TransferSession, TransferSessionCloseOptions, TransferStatus } from '../transfer/session'
import { applyMetadata } from '../seo/metadata'
import { ThemeToggle } from './ThemeToggle'
import { PublicSiteConfig, siteConfig } from '../site/content'
import { PeerRuntime } from '../webrtc/peer'

type Phase = 'checking-network' | 'checking-code' | 'waiting' | 'connecting' | 'connected' | 'failed'
type ConnectionMode = 'local' | 'peer'
type ConnectionPath = 'direct' | 'unknown'
type StepState = 'wait' | 'checking' | 'passed' | 'pairing' | 'done' | 'failed' | 'success'
type PeerCodeValidationResult = 'ok' | 'invalid' | 'self' | 'refreshed'

export interface PeerMessageItem extends TextExchange {
  // Set when the sender retracted the message; the chat shows a system note.
  recalled?: boolean
}
type InitialLocaleSource = 'url' | 'stored' | 'browser' | 'fallback'
type PairingQrState = { code: string; dataUrl: string }
type LocalRoomRefreshReason = 'initial' | 'manual' | 'timeout'
type ConnectionExitReason = 'active_disconnect' | 'remote_room_closed' | 'session_expired' | 'transient_disconnect' | 'direct_connection_failed'
export type ReconnectSource = 'signaling' | 'heartbeat' | 'peer-left' | 'transport'
export type AppReceivedFile = ReceivedFile & {
  receivedAtMs: number
}

const ttlSeconds = 120
// Upkeep has two speeds: a live transfer needs to notice a drop quickly, while
// a code waiting for a peer only needs the room kept open.
export const heartbeatIntervalMs = 25_000
export const waitingHeartbeatIntervalMs = 60_000
// A switched network kills the peer link before anything else notices. ICE
// often heals it within a second or two, so the link is given this long to come
// back before the person is told to reconnect by hand.
export const networkSwitchGraceMs = 5_000
const reconnectWindowMs = 5 * 60_000
const reconnectBaseDelayMs = 1_000
const reconnectMaxDelayMs = 10_000
const localRoomRetryBaseDelayMs = 2_000
const localRoomRetryMaxDelayMs = 30_000
const localRoomRetryWindowMs = 60_000
const browserDownloadObjectUrlReleaseDelayMs = 30 * 60_000
export const directConnectionFailureTimeoutMs = 30_000
const directConnectionFailureReason = 'Direct connection failed.'
const defaultFooterCopyright = '© 2026 Lan-Share. All rights reserved.'
const pairingCodeParam = 'pair'
const defaultTransferLimits: TransferLimits = {
  maxFileSize: 209_715_200,
  chunkSize: 262_144,
}
export const googlePlayDownload = {
  label: 'Google Play',
  url: 'https://play.google.com/store/apps/details?id=com.lanshare',
  target: '_blank',
  rel: 'noopener noreferrer',
} as const

export function App() {
  const initialLocale = useMemo(() => resolveInitialLocale(localStorage.getItem('lanshare.locale'), window.location.pathname, window.location.search, browserLocaleCandidates(), siteConfig.defaultLocale), [])
  const [locale, setLocale] = useState<Locale>(initialLocale.locale)
  const [languageMenuOpen, setLanguageMenuOpen] = useState(false)
  const t = (key: Parameters<typeof translate>[1]) => translate(locale, key)
  const localDevice = useMemo(() => getLocalDevice(), [])
  const [localCode, setLocalCode] = useState('')
  const [invalidatedCodes, setInvalidatedCodes] = useState<string[]>([])
  const [peerCode, setPeerCode] = useState('')
  const [incomingPairingCode, setIncomingPairingCode] = useState(() => pairingCodeFromURL(window.location.search))
  const [pairingQr, setPairingQr] = useState<PairingQrState>({ code: '', dataUrl: '' })
  const [peerConnectLoading, setPeerConnectLoading] = useState(false)
  const [codeCopied, setCodeCopied] = useState(false)
  const [phase, setPhase] = useState<Phase>('checking-network')
  const [mode, setMode] = useState<ConnectionMode>('local')
  const [countdown, setCountdown] = useState(ttlSeconds)
  const [pairingExpiresAtMs, setPairingExpiresAtMs] = useState(() => Date.now() + ttlSeconds * 1000)
  const [failure, setFailure] = useState('')
  const [directFailureNotice, setDirectFailureNotice] = useState('')
  const [heartbeatWindow, setHeartbeatWindow] = useState<boolean[]>([])
  const [connectionPath, setConnectionPath] = useState<ConnectionPath>('unknown')
  const [activeCode, setActiveCode] = useState(localCode)
  const [localFiles, setLocalFiles] = useState<TransferFileItem[]>([])
  const [remoteFiles, setRemoteFiles] = useState<TransferFileItem[]>([])
  const [peerMessages, setPeerMessages] = useState<PeerMessageItem[]>([])
  const [receivedFiles, setReceivedFiles] = useState<AppReceivedFile[]>([])
  const [transferStatus, setTransferStatus] = useState<TransferStatus>('idle')
  const [transferFailure, setTransferFailure] = useState('')
  const transferLimitsRef = useRef<TransferLimits>(defaultTransferLimits)
  const [signaling, setSignaling] = useState<SignalingSession | null>(null)
  const [dataChannelOpen, setDataChannelOpen] = useState(false)
  const [peerDeviceLabel, setPeerDeviceLabel] = useState('')
  // Mobile-only: the paired code and diagnostics leave the page flow and live
  // in a bottom sheet, the way the apps present connection details over the
  // chat. Desktop keeps the side column and never opens the sheet.
  const [detailsOpen, setDetailsOpen] = useState(false)
  const signalingRef = useRef<SignalingSession | null>(null)
  const activeRoomRef = useRef<CloseRoomRequest | null>(null)
  const heartbeatWindowRef = useRef<boolean[]>([])
  // Only the transition into a broken window may start a reconnect, so a long
  // outage does not restart the grace window on every subsequent beat.
  const heartbeatBrokenRef = useRef(false)
  const networkSwitchTimerRef = useRef<number | null>(null)
  const iceRestartAttemptsRef = useRef(0)
  // Bumped whenever the current link stops being the thing to watch: a timer
  // armed for an older link must not fire against a newer one.
  const linkGenerationRef = useRef(0)
  const sessionExpiresAtRef = useRef<string | null>(null)
  const pairingExpiresAtMsRef = useRef(pairingExpiresAtMs)
  const peerRuntimeRef = useRef<PeerRuntime | null>(null)
  const transferSessionRef = useRef<TransferSession | null>(null)
  const phaseRef = useRef<Phase>(phase)
  const dataChannelOpenRef = useRef(dataChannelOpen)
  const retainedLocalFilesRef = useRef(new Map<string, File>())
  const requestedDownloadFileIdsRef = useRef(new Set<string>())
  const downloadAnchorRef = useRef<HTMLAnchorElement | null>(null)
  const scannedPairingLinkRef = useRef(Boolean(incomingPairingCode))
  const browserDownloadUrlsRef = useRef(new Set<string>())
  const deferredObjectUrlRevokeTimersRef = useRef(new Map<string, number>())
  const footerRef = useRef<HTMLElement | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectExpiryTimerRef = useRef<number | null>(null)
  const localRoomRetryTimerRef = useRef<number | null>(null)
  const directConnectionFailureTimerRef = useRef<number | null>(null)
  const copyResetTimerRef = useRef<number | null>(null)
  const pairingCountdownPausedAtRef = useRef<number | null>(null)
  const reconnectDeadlineRef = useRef(0)
  const reconnectAttemptRef = useRef(0)
  const localRoomRetryAttemptRef = useRef(0)
  const localRoomRetryDeadlineRef = useRef(0)
  const createRoomRequestRef = useRef(0)
  const intentionalSignalingCloseRef = useRef(false)
  const intentionallyClosedPeerRuntimesRef = useRef<WeakSet<PeerRuntime>>(new WeakSet())
  const peerConnectInFlightRef = useRef(false)
  const reconnectSourceRef = useRef<ReconnectSource | null>(null)
  const peerDeviceIdRef = useRef('')
  const modeRef = useRef<ConnectionMode>(mode)
  const connectionPathRef = useRef<ConnectionPath>('unknown')
  const languageMenuRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!incomingPairingCode) return
    window.history.replaceState(window.history.state, '', safePairingURLPath(window.location.pathname + window.location.search + window.location.hash))
    setPeerCode(incomingPairingCode)
    if (canAutoJoinIncomingPairingCode(incomingPairingCode, peerConnectInFlightRef.current, mode, phase)) {
      void connectPeer(incomingPairingCode)
    }
  }, [incomingPairingCode])

  useEffect(() => {
    let cancelled = false
    setPairingQr({ code: '', dataUrl: '' })
    if (!localCode || phase === 'connected') return
    const pairingUrl = buildPairingURL(window.location.href, localCode, locale)
    const qrCode = localCode
    void QRCode.toDataURL(pairingUrl, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 176,
      color: {
        dark: '#10201a',
        light: '#ffffff',
      },
    }).then((dataUrl) => {
      if (!cancelled) setPairingQr({ code: qrCode, dataUrl })
    }).catch(() => {
      if (!cancelled) setPairingQr({ code: '', dataUrl: '' })
    })
    return () => {
      cancelled = true
    }
  }, [localCode, locale, phase])

  useEffect(() => {
    syncAutoLocalePath(initialLocale.source, initialLocale.locale)
  }, [])

  useEffect(() => {
    modeRef.current = mode
  }, [mode])

  useEffect(() => {
    return () => {
      if (copyResetTimerRef.current !== null) window.clearTimeout(copyResetTimerRef.current)
      clearDirectConnectionFailureTimer()
      clearLocalRoomRetryTimer()
    }
  }, [])

  useEffect(() => {
    document.documentElement.style.setProperty('--accent', siteConfig.primaryColor)
    document.documentElement.style.setProperty('--blue', siteConfig.accentColor)
  }, [])

  useEffect(() => {
    applyMetadata(siteConfig.siteName || t('appName'), t('tagline'), locale)
  }, [siteConfig.siteName, locale])

  useEffect(() => {
    document.documentElement.lang = locale
    document.documentElement.dir = localeDirection(locale)
  }, [locale])

  const pairingCountdownPaused = shouldPausePairingCountdown(mode, phase, peerConnectLoading)

  useEffect(() => {
    if (pairingCountdownPaused) {
      if (pairingCountdownPausedAtRef.current === null) pairingCountdownPausedAtRef.current = Date.now()
      return
    }
    const pausedAt = pairingCountdownPausedAtRef.current
    if (pausedAt === null) return
    pairingCountdownPausedAtRef.current = null
    if (!isPairingCountdownPhase(phase)) return
    setPairingDeadline(resumePairingDeadlineAfterPause(pairingExpiresAtMsRef.current, pausedAt, Date.now()))
  }, [pairingCountdownPaused, phase])

  function changeLocale(nextLocale: string) {
    const normalized = normalizeLocale(nextLocale)
    localStorage.setItem('lanshare.locale', normalized)
    replaceLocalePath(normalized)
    setLocale(normalized)
    setLanguageMenuOpen(false)
  }

  useEffect(() => {
    if (!languageMenuOpen) return
    const closeMenu = (event: MouseEvent) => {
      if (!languageMenuRef.current?.contains(event.target as Node)) setLanguageMenuOpen(false)
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setLanguageMenuOpen(false)
    }
    document.addEventListener('mousedown', closeMenu)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('mousedown', closeMenu)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [languageMenuOpen])

  useEffect(() => {
    if (!detailsOpen) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDetailsOpen(false)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [detailsOpen])

  useEffect(() => {
    if (!shouldAutoCreateLocalRoom(navigator.userAgent, navigator.webdriver, scannedPairingLinkRef.current)) return
    let cancelled = false
    void createLocalRoom('initial', () => cancelled)
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (!isPairingCountdownPhase(phase) || pairingCountdownPaused) return
    let refreshed = false
    const syncCountdown = () => {
      const remaining = secondsUntil(pairingExpiresAtMsRef.current)
      if (remaining <= 0) {
        // A waiting code renews itself so the screen never shows a dead one.
        // The renewal is upkeep, not news: any blip along the way stays silent.
        if (refreshed) return
        refreshed = true
        refreshLocalCode('timeout')
        return
      }
      setCountdown(remaining)
    }
    syncCountdown()
    const timer = window.setInterval(syncCountdown, 1000)
    window.addEventListener('focus', syncCountdown)
    window.addEventListener('pageshow', syncCountdown)
    document.addEventListener('visibilitychange', syncCountdown)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', syncCountdown)
      window.removeEventListener('pageshow', syncCountdown)
      document.removeEventListener('visibilitychange', syncCountdown)
    }
  }, [phase, pairingExpiresAtMs, pairingCountdownPaused])

  useEffect(() => {
    const closeOnUnload = () => {
      if (activeRoomRef.current) closeRoomBeacon(activeRoomRef.current)
    }
    window.addEventListener('beforeunload', closeOnUnload)
    return () => window.removeEventListener('beforeunload', closeOnUnload)
  }, [])

  useEffect(() => {
    if (!signaling || !activeRoomRef.current) return
    // Nothing to keep alive before a room is paired, and a waiting code only
    // needs the session kept open; only a live connection demands upkeep.
    const interval = heartbeatIntervalForPhase(phase)
    if (interval === null) return
    // A fresh stretch of upkeep starts with a clean slate: failures from an
    // earlier phase say nothing about this one.
    heartbeatWindowRef.current = []
    heartbeatBrokenRef.current = false
    let stopped = false
    const heartbeat = () => {
      const activeRoom = activeRoomRef.current
      if (!activeRoom || stopped) return
      void sendRoomHeartbeat(activeRoom)
        .then((response) => {
          if (stopped) return
          if (!isCurrentRoomRequest(activeRoom, activeRoomRef.current)) return
          sessionExpiresAtRef.current = response.sessionExpiresAt
          recordHeartbeatResult(true)
        })
        .catch((error) => {
          if (stopped) return
          if (!isCurrentRoomRequest(activeRoom, activeRoomRef.current)) return
          recordHeartbeatResult(false, error)
        })
    }
    heartbeat()
    const timer = window.setInterval(heartbeat, interval)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [signaling, locale, phase])

  useEffect(() => {
    clearDirectConnectionFailureTimer()
    if (!shouldMonitorDirectConnectionAttempt(phase, dataChannelOpen)) return
    const startedAtMs = Date.now()
    directConnectionFailureTimerRef.current = window.setTimeout(() => {
      directConnectionFailureTimerRef.current = null
      if (!shouldFailDirectConnectionAttempt(phaseRef.current, dataChannelOpenRef.current, startedAtMs, Date.now())) return
      handlePeerConnectionFailure(directConnectionFailureReason)
    }, directConnectionFailureTimeoutMs)
    return clearDirectConnectionFailureTimer
  }, [phase, dataChannelOpen])

  function refreshLocalCode(reason: LocalRoomRefreshReason) {
    if (reason !== 'timeout') {
      localRoomRetryAttemptRef.current = 0
      localRoomRetryDeadlineRef.current = 0
    }
    void createLocalRoom(reason)
  }

  async function createLocalRoom(reason: LocalRoomRefreshReason, isCancelled: () => boolean = () => false) {
    const requestId = createRoomRequestRef.current + 1
    createRoomRequestRef.current = requestId
    peerConnectInFlightRef.current = false
    pairingCountdownPausedAtRef.current = null
    resetConnectionFormAfterExit()
    clearLocalRoomRetryTimer()
    setCountdown(ttlSeconds)
    setPairingDeadline(Date.now() + ttlSeconds * 1000)
    setFailure('')
    setConnectionPhase('checking-network')
    resetTransfer()
    setDataChannelOpenState(false)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    try {
      await closeActiveRoom()
      if (!shouldContinueConnectionRequest(requestId, createRoomRequestRef.current, isCancelled())) return
      replaceSignaling(null)
      if (localCode) setInvalidatedCodes((items) => [localCode, ...items].slice(0, 8))
      setLocalCode('')
      replacePeerRuntime(null, { status: 'canceled' })
      const created = await createRoom(localDevice)
      if (!shouldContinueConnectionRequest(requestId, createRoomRequestRef.current, isCancelled())) {
        void closeRoom(created).catch(() => undefined)
        return
      }
      setLocalCode(created.code)
      setActiveCode(created.code)
      setActiveRoom(created)
      setPairingDeadline(Date.parse(created.pairingExpiresAt))
      transferLimitsRef.current = {
        maxFileSize: created.limits.maxFileSize,
        chunkSize: created.limits.chunkSize,
      }
      let activeSession: SignalingSession | null = null
      const pendingMessages: SignalingMessage[] = []
      const session = await openSignaling(created, (message) => {
        if (activeSession) void handleSignalMessage(message, activeSession)
        else pendingMessages.push(message)
      }, handleSignalingDisconnect)
      activeSession = session
      pendingMessages.splice(0).forEach((message) => void handleSignalMessage(message, session))
      if (!shouldContinueConnectionRequest(requestId, createRoomRequestRef.current, isCancelled())) {
        session.close()
        void closeRoom(created).catch(() => undefined)
        return
      }
      replaceSignaling(session)
      localRoomRetryAttemptRef.current = 0
      localRoomRetryDeadlineRef.current = 0
      setConnectionPhase('waiting')
    } catch (error) {
      if (!shouldContinueConnectionRequest(requestId, createRoomRequestRef.current, isCancelled())) return
      // Refreshing a code is upkeep the person asked for, not a failure they
      // need to read: the page keeps trying and only a code that never arrives
      // changes what is on screen.
      const terminal = isTerminalLocalRoomFailure(error)
      if (terminal) {
        stopLocalRoomRetry()
        setFailure(errorMessage(error, t('errorConnectionSetupFailed')))
        setConnectionPhase('failed')
        return
      }
      setFailure('')
      setConnectionPhase('checking-network')
      // A manual refresh retries as an initial request: the server never got to
      // judge the attempt, so it is not a refusal that should stop the loop.
      scheduleLocalRoomRetry(reason === 'manual' ? 'initial' : reason)
    }
  }

  async function copyLocalCode() {
    try {
      await copyText(localCode)
      setCodeCopied(true)
      if (copyResetTimerRef.current !== null) window.clearTimeout(copyResetTimerRef.current)
      copyResetTimerRef.current = window.setTimeout(() => setCodeCopied(false), 1600)
    } catch {
      setCodeCopied(false)
      setFailure(t('copyFailed'))
    }
  }

  function setActiveRoom(joined: CloseRoomRequest & { sessionExpiresAt?: string | null }) {
    activeRoomRef.current = joined
    if (joined.sessionExpiresAt !== undefined) sessionExpiresAtRef.current = joined.sessionExpiresAt
  }

  function setPairingDeadline(expiresAtMs: number) {
    pairingExpiresAtMsRef.current = expiresAtMs
    setPairingExpiresAtMs(expiresAtMs)
    setCountdown(secondsUntil(expiresAtMs))
  }

  function closeActiveRoom(): Promise<void> {
    const current = activeRoomRef.current
    activeRoomRef.current = null
    sessionExpiresAtRef.current = null
    clearReconnectTimer()
    if (!current) return Promise.resolve()
    return closeRoom(current).catch(() => undefined)
  }

  function handleRemoteRoomClosed(reason: string) {
    const shouldShowDirectFailureNotice = shouldShowDirectFailureAfterRemoteRoomClosed(modeRef.current, phaseRef.current, dataChannelOpenRef.current)
    peerConnectInFlightRef.current = false
    activeRoomRef.current = null
    sessionExpiresAtRef.current = null
    clearReconnectTimer()
    clearNetworkSwitchTimer()
    iceRestartAttemptsRef.current = 0
    replaceSignaling(null)
    replacePeerRuntime(null, { status: 'canceled' })
    resetConnectionFormAfterExit()
    resetTransfer()
    setFailure(reason)
    setDataChannelOpenState(false)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    setConnectionPhase('failed')
    if (shouldShowDirectFailureNotice) setDirectFailureNotice(directConnectionFailureReason)
    refreshLocalRoomAfterConnectionExit('remote_room_closed')
  }

  // Someone closing their tab is not a fault to interrupt anyone over: the
  // page returns to waiting and says so in place, with the way back at hand.
  function handlePeerLeft() {
    const reason = t('errorPeerDisconnected')
    peerConnectInFlightRef.current = false
    activeRoomRef.current = null
    sessionExpiresAtRef.current = null
    clearReconnectTimer()
    // The peer is gone; a pending switch verdict would blame the network for
    // something the other person did on purpose.
    clearNetworkSwitchTimer()
    iceRestartAttemptsRef.current = 0
    replaceSignaling(null)
    replacePeerRuntime(null, { status: 'canceled' })
    resetConnectionFormAfterExit()
    resetTransfer()
    setDataChannelOpenState(false)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    setConnectionPhase('failed')
    setFailure(reason)
    refreshLocalRoomAfterConnectionExit('remote_room_closed')
  }

  function recordHeartbeatResult(ok: boolean, error?: unknown) {
    if (!ok && error !== undefined && isTerminalHeartbeatError(error)) {
      handleTerminalHeartbeatFailure()
      return
    }
    // A server-side answer we cannot interpret still counts as a miss, but a
    // single miss must never move the UI: only the window's verdict does.
    const next = pushHeartbeatResult(heartbeatWindowRef.current, ok)
    heartbeatWindowRef.current = next
    setHeartbeatWindow(next)
    const health = heartbeatHealth(next)
    // A heartbeat is upkeep, not news: the person asked for a file transfer, not
    // a running report on the connection. Recover in the background and leave
    // the page alone until there is something they can actually act on.
    if (health === 'broken') {
      if (!heartbeatBrokenRef.current) {
        heartbeatBrokenRef.current = true
        scheduleReconnect(errorMessage(error, t('errorNetworkDisconnected')), 'heartbeat')
      }
      return
    }
    if (health === 'healthy') heartbeatBrokenRef.current = false
  }

  function handleTerminalHeartbeatFailure() {
    if (isPairingCountdownPhase(phase) && secondsUntil(pairingExpiresAtMsRef.current) <= 0) {
      // The countdown owns renewal; a heartbeat landing in that gap must not
      // start a second one or report it.
      return
    }
    handleSessionExpired()
  }

  function handleSessionExpired() {
    const reason = t('errorSessionExpired')
    peerConnectInFlightRef.current = false
    activeRoomRef.current = null
    sessionExpiresAtRef.current = null
    clearReconnectTimer()
    replaceSignaling(null)
    replacePeerRuntime(null, { status: 'canceled' })
    resetConnectionFormAfterExit()
    resetTransfer()
    setFailure(reason)
    setDataChannelOpenState(false)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    setConnectionPhase('failed')
    refreshLocalRoomAfterConnectionExit('session_expired')
  }

  function handleTransientDisconnect(reason: string, source: ReconnectSource) {
    scheduleReconnect(reason, source)
  }

  function refreshLocalRoomAfterConnectionExit(reason: ConnectionExitReason) {
    const refreshReason = localRoomRefreshReasonAfterConnectionExit(reason)
    if (refreshReason) refreshLocalCode(refreshReason)
  }

  function clearReconnectTimer() {
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
    }
    if (reconnectExpiryTimerRef.current !== null) {
      window.clearTimeout(reconnectExpiryTimerRef.current)
      reconnectExpiryTimerRef.current = null
    }
    reconnectDeadlineRef.current = 0
    reconnectAttemptRef.current = 0
    reconnectSourceRef.current = null
  }

  function ensureReconnectExpiryTimer() {
    if (reconnectExpiryTimerRef.current !== null || reconnectDeadlineRef.current === 0) return
    const delay = Math.max(reconnectDeadlineRef.current - Date.now(), 0)
    reconnectExpiryTimerRef.current = window.setTimeout(() => {
      reconnectExpiryTimerRef.current = null
      if (!activeRoomRef.current || reconnectDeadlineRef.current === 0) return
      if (Date.now() < reconnectDeadlineRef.current) {
        ensureReconnectExpiryTimer()
        return
      }
      const source = reconnectSourceRef.current || 'signaling'
      handleRemoteRoomClosed(t('errorReconnectExpired'))
    }, delay)
  }

  function clearLocalRoomRetryTimer() {
    if (localRoomRetryTimerRef.current !== null) {
      window.clearTimeout(localRoomRetryTimerRef.current)
      localRoomRetryTimerRef.current = null
    }
  }

  function clearDirectConnectionFailureTimer() {
    if (directConnectionFailureTimerRef.current !== null) {
      window.clearTimeout(directConnectionFailureTimerRef.current)
      directConnectionFailureTimerRef.current = null
    }
  }

  function scheduleLocalRoomRetry(reason: 'initial' | 'timeout') {
    clearLocalRoomRetryTimer()
    const now = Date.now()
    if (localRoomRetryDeadlineRef.current === 0) localRoomRetryDeadlineRef.current = now + localRoomRetryWindowMs
    const remainingMs = localRoomRetryDeadlineRef.current - now
    if (localRoomRetryWindowExpired(localRoomRetryDeadlineRef.current, now)) {
      // The server stayed unreachable for the whole retry window: stop looping
      // and hand the decision back to the person in front of the screen. This is
      // the one moment worth a message, since nothing else on the page explains
      // why the code stopped changing.
      stopLocalRoomRetry()
      setFailure(t('errorConnectionSetupFailed'))
      setConnectionPhase('failed')
      return
    }
    const delay = Math.min(localRoomRetryDelay(localRoomRetryAttemptRef.current), remainingMs)
    localRoomRetryAttemptRef.current += 1
    localRoomRetryTimerRef.current = window.setTimeout(() => {
      localRoomRetryTimerRef.current = null
      void createLocalRoom(reason)
    }, delay)
  }

  function stopLocalRoomRetry() {
    clearLocalRoomRetryTimer()
    localRoomRetryAttemptRef.current = 0
    localRoomRetryDeadlineRef.current = 0
  }

  function scheduleReconnect(reason: string, source: ReconnectSource) {
    const activeRoom = activeRoomRef.current
    if (!activeRoom) return
    if (isSessionExpired()) {
      handleSessionExpired()
      return
    }
    if (reconnectDeadlineRef.current === 0) reconnectDeadlineRef.current = Date.now() + reconnectWindowMs
    ensureReconnectExpiryTimer()
    if (Date.now() > reconnectDeadlineRef.current) {
      handleRemoteRoomClosed(t('errorReconnectExpired'))
      return
    }
    const resetPeerRuntime = shouldResetPeerRuntimeForReconnect(phaseRef.current, dataChannelOpenRef.current, source)
    if (reconnectTimerRef.current === null) {
      reconnectSourceRef.current = source
      console.info('[LanShare] reconnect grace window started', {
        source,
        reason,
        phase: phaseRef.current,
        connectionPath: connectionPathRef.current,
        dataChannelOpen: dataChannelOpenRef.current,
        deadlineMs: reconnectDeadlineRef.current,
      })
    }
    // A heartbeat reconnect is routine upkeep; surfacing it would turn a
    // two-second blip into an alarming banner.
    if (source !== 'heartbeat' && shouldShowReconnectNotice(phaseRef.current, dataChannelOpenRef.current, source)) setFailure(reason)
    if (resetPeerRuntime) {
      setConnectionPhase('connecting')
      setDataChannelOpenState(false)
      replacePeerRuntime(null)
    }
    if (reconnectTimerRef.current !== null) return
    const delay = Math.min(reconnectBaseDelayMs * 2 ** reconnectAttemptRef.current, reconnectMaxDelayMs)
    reconnectAttemptRef.current += 1
    reconnectTimerRef.current = window.setTimeout(() => {
      reconnectTimerRef.current = null
      void reconnectSignaling()
    }, delay)
  }

  async function reconnectSignaling() {
    const activeRoom = activeRoomRef.current
    if (!activeRoom) return
    if (isSessionExpired()) {
      handleSessionExpired()
      return
    }
    try {
      let activeSession: SignalingSession | null = null
      const pendingMessages: SignalingMessage[] = []
      const session = await openSignaling(activeRoom, (message) => {
        if (activeSession) void handleSignalMessage(message, activeSession)
        else pendingMessages.push(message)
      }, handleSignalingDisconnect)
      activeSession = session
      pendingMessages.splice(0).forEach((message) => void handleSignalMessage(message, session))
      replaceSignaling(session)
      const reconnectSource = reconnectSourceRef.current
      setConnectionPhase((current) => phaseAfterSignalingReconnect(current, modeRef.current, peerDeviceIdRef.current !== ''))
      setFailure('')
      if (shouldClearReconnectWindowAfterSignalingReconnect(reconnectSource)) {
        clearReconnectTimer()
      }
      void updatePeerDeviceLabel(activeRoom)
      void settleAfterSignalingReconnected(activeRoom).catch((error) => {
        // A failed probe leaves the room as it was; the next heartbeat decides.
        console.info('[LanShare] could not settle after reconnect', { error: String(error) })
      })
    } catch (error) {
      scheduleReconnect(errorMessage(error, t('errorNetworkDisconnected')), 'signaling')
    }
  }

  // Signaling is the only party that knows whether the peer is still there.
  // Coming back to it is therefore the moment the cause becomes knowable: the
  // peer is present and the transfer link is not, which means this device moved
  // to a different network and the old link is beyond healing.
  async function settleAfterSignalingReconnected(activeRoom: CloseRoomRequest) {
    const status = await getRoomStatus(activeRoom)
    if (!isCurrentRoomRequest(activeRoom, activeRoomRef.current)) return
    if (peerPresenceAfterReconnect(status, activeRoom.deviceId) === 'gone') {
      // The other person left; their absence is not a network fault.
      handlePeerLeft()
      return
    }
    if (!dataChannelOpenRef.current) {
      console.info('[LanShare] signaling recovered but the transfer link did not; network changed')
      setDirectFailureNotice(t('networkSwitched'))
    }
  }

  function handleSignalingDisconnect(event: CloseEvent) {
    if (intentionalSignalingCloseRef.current) return
    console.info('[LanShare] signaling disconnected; scheduling reconnect', {
      code: event.code,
      reason: event.reason,
      wasClean: event.wasClean,
      phase: phaseRef.current,
      connectionPath: connectionPathRef.current,
      dataChannelOpen: dataChannelOpenRef.current,
    })
    handleTransientDisconnect(t('errorNetworkDisconnected'), 'signaling')
  }

  function isSessionExpired(): boolean {
    const expiresAt = sessionExpiresAtRef.current
    return !!expiresAt && Date.now() >= Date.parse(expiresAt)
  }

  function replaceSignaling(next: SignalingSession | null) {
    if (signalingRef.current) {
      intentionalSignalingCloseRef.current = true
      signalingRef.current.close()
      window.setTimeout(() => {
        intentionalSignalingCloseRef.current = false
      }, 0)
    }
    signalingRef.current = next
    setSignaling(next)
  }

  function replacePeerRuntime(next: PeerRuntime | null, transferCloseOptions: TransferSessionCloseOptions = { status: 'failed', reason: t('errorNetworkDisconnected') }) {
    if (peerRuntimeRef.current) {
      intentionallyClosedPeerRuntimesRef.current.add(peerRuntimeRef.current)
      peerRuntimeRef.current.close()
    }
    peerRuntimeRef.current = next
    setDataChannelOpenState(false)
    replaceTransferSession(null, transferCloseOptions)
  }

  function setConnectionPhase(next: Phase | ((current: Phase) => Phase)) {
    setPhase((current) => {
      const resolved = typeof next === 'function' ? next(current) : next
      phaseRef.current = resolved
      return resolved
    })
  }

  function setDataChannelOpenState(next: boolean) {
    dataChannelOpenRef.current = next
    setDataChannelOpen(next)
  }

  function setConnectionPathState(next: ConnectionPath) {
    connectionPathRef.current = next
    setConnectionPath(next)
  }

  function replaceTransferSession(next: TransferSession | null, closeOptions?: TransferSessionCloseOptions) {
    transferSessionRef.current?.close(closeOptions)
    transferSessionRef.current = next
  }

  function scheduleObjectUrlRevoke(url: string) {
    if (deferredObjectUrlRevokeTimersRef.current.has(url)) return
    const timer = window.setTimeout(() => {
      deferredObjectUrlRevokeTimersRef.current.delete(url)
      browserDownloadUrlsRef.current.delete(url)
      URL.revokeObjectURL(url)
    }, browserDownloadObjectUrlReleaseDelayMs)
    deferredObjectUrlRevokeTimersRef.current.set(url, timer)
  }

  function releaseReceivedFiles(files: ReceivedFile[]) {
    files.forEach((file) => {
      if (browserDownloadUrlsRef.current.has(file.url)) {
        scheduleObjectUrlRevoke(file.url)
        return
      }
      URL.revokeObjectURL(file.url)
    })
  }

  function resetTransfer() {
    releaseReceivedFiles(receivedFiles)
    setLocalFiles([])
    setRemoteFiles([])
    setReceivedFiles([])
    setPeerMessages([])
    setTransferStatus('idle')
    setTransferFailure('')
    retainedLocalFilesRef.current.clear()
    requestedDownloadFileIdsRef.current.clear()
  }

  async function updatePeerDeviceLabel(joined: CloseRoomRequest) {
    try {
      const status = await getRoomStatus(joined)
      sessionExpiresAtRef.current = status.sessionExpiresAt
      const peer = status.peers.find((item) => item.deviceId !== joined.deviceId)
      if (peer?.deviceId) peerDeviceIdRef.current = peer.deviceId
      if (peer?.deviceLabel) setPeerDeviceLabel(peer.deviceLabel)
    } catch {
      // Best-effort display data. Pairing and transfer continue over signaling.
    }
  }

  function peerLabelFromSignal(message: SignalingMessage): string {
    const payload = message.payload
    if (!payload || typeof payload !== 'object' || !('deviceLabel' in payload)) return ''
    return String((payload as { deviceLabel?: unknown }).deviceLabel || '').trim()
  }

  function createPeerRuntime(session: SignalingSession): PeerRuntime {
    let runtime: PeerRuntime | null = null
    runtime = new PeerRuntime({
      iceServers: siteConfig.rtc.iceServers,
      sendSignal: session.send,
      onOpen: (channel) => {
        clearReconnectTimer()
        clearNetworkSwitchTimer()
        iceRestartAttemptsRef.current = 0
        replaceTransferSession(createTransferSession(channel), { status: 'failed', reason: t('errorNetworkDisconnected') })
        setDataChannelOpenState(true)
        setFailure('')
        setDirectFailureNotice('')
        // The link is back; an interruption banner left over from the old
        // channel no longer describes anything the person can act on.
        setTransferFailure('')
        setConnectionPathState('direct')
        setConnectionPhase('connected')
      },
      onState: (state) => {
        if (state === 'connecting' && !dataChannelOpenRef.current) setConnectionPhase('connecting')
        if (state === 'connected') {
          clearNetworkSwitchTimer()
          iceRestartAttemptsRef.current = 0
        }
        if (state === 'disconnected') handlePeerLinkLost(runtime)
        if (state === 'closed' && runtime && !intentionallyClosedPeerRuntimesRef.current.has(runtime)) {
          handleTransientDisconnect(t('errorNetworkDisconnected'), 'transport')
        }
      },
      onFailure: (reason) => {
        handlePeerConnectionFailure(reason)
      },
    })
    replacePeerRuntime(runtime)
    return runtime
  }

  function clearNetworkSwitchTimer() {
    linkGenerationRef.current += 1
    if (networkSwitchTimerRef.current !== null) {
      window.clearTimeout(networkSwitchTimerRef.current)
      networkSwitchTimerRef.current = null
    }
  }

  function handlePeerLinkLost(runtime: PeerRuntime | null) {
    // A link that was never open is still pairing, not switching networks.
    if (!runtime) return
    if (!shouldReactToPeerLinkLoss(true, dataChannelOpenRef.current)) return
    // A dead link looks identical whether this device changed networks or the
    // other person closed their tab, so the link itself is never enough to
    // convict the network. Try to heal it, and let the server say who left.
    if (iceRestartAttemptsRef.current > 0) return
    iceRestartAttemptsRef.current += 1
    if (!signalingRef.current) return
    void runtime.restartIce().catch((error) => {
      console.info('[LanShare] ice restart failed', { error: String(error) })
    })
  }

  function handlePeerConnectionFailure(reason: string) {
    if (shouldPreserveConnectedSessionDuringReconnect(phaseRef.current, dataChannelOpenRef.current, 'transport')) {
      console.info('[LanShare] peer transport failed inside reconnect grace window', {
        reason,
        phase: phaseRef.current,
        connectionPath: connectionPathRef.current,
        dataChannelOpen: dataChannelOpenRef.current,
      })
      handleTransientDisconnect(reason, 'transport')
      return
    }
    clearDirectConnectionFailureTimer()
    clearReconnectTimer()
    peerConnectInFlightRef.current = false
    setDirectFailureNotice(reason)
    replaceSignaling(null)
    replacePeerRuntime(null, { status: 'canceled' })
    resetConnectionFormAfterExit()
    resetTransfer()
    setFailure('')
    setDataChannelOpenState(false)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    refreshLocalRoomAfterConnectionExit('direct_connection_failed')
  }

  function createTransferSession(channel: RTCDataChannel): TransferSession {
    return new TransferSession(channel, {
      limits: () => transferLimitsRef.current,
      onLocalOffer: (item) => {
        setLocalFiles((current) => upsertTransferItem(current, item))
        setTransferFailure('')
      },
      onRemoteOffer: (item) => {
        setRemoteFiles((current) => upsertTransferItem(current, item))
        setTransferFailure('')
      },
      onStatus: (fileId, status, reason) => {
        setTransferStatus(status)
        setTransferFailure(status === 'canceled' ? '' : reason || '')
        if (status === 'canceled') {
          retainedLocalFilesRef.current.delete(fileId)
          requestedDownloadFileIdsRef.current.delete(fileId)
          setLocalFiles((current) => removeTransferItem(current, fileId))
          setRemoteFiles((current) => removeTransferItem(current, fileId))
          setReceivedFiles((current) => {
            releaseReceivedFiles(current.filter((file) => file.offer.fileId === fileId))
            return current.filter((file) => file.offer.fileId !== fileId)
          })
          return
        }
        if (status === 'failed') requestedDownloadFileIdsRef.current.delete(fileId)
        if (status === 'complete') retainedLocalFilesRef.current.delete(fileId)
        setLocalFiles((current) => updateTransferItem(current, fileId, { status, failure: reason || '' }))
        setRemoteFiles((current) => updateTransferItem(current, fileId, { status, failure: reason || '' }))
      },
      onProgress: (fileId, nextProgress) => {
        setLocalFiles((current) => updateTransferItem(current, fileId, { progress: nextProgress }))
        setRemoteFiles((current) => updateTransferItem(current, fileId, { progress: nextProgress }))
      },
      onComplete: (result) => {
        const received: AppReceivedFile = {
          ...result,
          receivedAtMs: Date.now(),
        }
        setReceivedFiles((current) => {
          releaseReceivedFiles(current.filter((file) => file.offer.fileId === received.offer.fileId))
          return upsertReceivedFile(current, received)
        })
        const shouldDownloadNow = shouldTriggerBrowserDownloadAfterReceive(received.offer.fileId, requestedDownloadFileIdsRef.current)
        requestedDownloadFileIdsRef.current.delete(received.offer.fileId)
        if (shouldDownloadNow) {
          triggerBrowserDownload(received)
        }
      },
      onError: (_fileId, reason) => setTransferFailure(reason),
      onChannelFailure: () => {
        handleRemoteRoomClosed(t('errorPeerDisconnected'))
      },
      onTextMessage: (message) => {
        setPeerMessages((current) => [...current, message])
      },
      onRecalled: (recall) => {
        if (recall.target === 'text') {
          setPeerMessages((current) => current.map((item) => (
            item.id === recall.targetId ? { ...item, recalled: true } : item
          )))
          return
        }
        const markRecalled = (item: TransferFileItem) => (
          item.offer.fileId === recall.targetId ? { ...item, recalled: true } : item
        )
        retainedLocalFilesRef.current.delete(recall.targetId)
        requestedDownloadFileIdsRef.current.delete(recall.targetId)
        setLocalFiles((current) => current.map(markRecalled))
        setRemoteFiles((current) => current.map(markRecalled))
        setReceivedFiles((current) => {
          releaseReceivedFiles(current.filter((file) => file.offer.fileId === recall.targetId))
          return current.filter((file) => file.offer.fileId !== recall.targetId)
        })
      },
    })
  }

  async function handleSignalMessage(message: SignalingMessage, session: SignalingSession) {
    try {
      // A peer_joined that names our own deviceId is a same-slot socket
      // re-authing (a second tab of this browser). Acting on it would pair us
      // against ourselves and orphan the real peer, and remembering it as the
      // peer would corrupt every later guard, so drop it before anything else.
      if (message.type === 'peer_joined' && shouldIgnoreSelfPeerJoined(message.from || '', activeRoomRef.current?.deviceId || '')) return
      if (message.from) peerDeviceIdRef.current = message.from
      if (message.type === 'room_closed') {
        handleRemoteRoomClosed(t('errorPeerDisconnected'))
        return
      }
      if (message.type === 'peer_left') {
        handlePeerLeft()
        return
      }
      if (message.type === 'peer_joined' && message.from) {
        const peerLabel = peerLabelFromSignal(message)
        if (peerLabel) setPeerDeviceLabel(peerLabel)
        // A peer re-authenticating its signaling socket is announced as
        // peer_joined. With the link to that same device still up this is
        // upkeep, not a new pairing: rebuilding the channel would swap out a
        // live transfer session and stamp the conversation with a
        // network-interrupted banner even though nothing was lost.
        if (shouldIgnorePeerJoinedReauth(message.from, peerDeviceIdRef.current, peerRuntimeRef.current?.isLinkHealthy() ?? false)) return
        modeRef.current = 'local'
        setMode('local')
        // peer_joined can arrive through a stale signaling closure whose
        // localCode was captured before the room opened; prefer whichever
        // code is available so the summary card never blanks or goes stale.
        setActiveCode((current) => preferredActiveCode(current, localCode))
        setConnectionPhase('connecting')
        const runtime = peerRuntimeRef.current || createPeerRuntime(session)
        await runtime.createOffer(message.from)
        return
      }
      if (['offer', 'answer', 'ice_candidate'].includes(message.type)) {
        const runtime = peerRuntimeRef.current || createPeerRuntime(session)
        await runtime.handleSignal(message)
      }
    } catch (error) {
      const reason = errorMessage(error, t('errorWebRTCConnectionFailed'))
      setFailure(reason)
      setConnectionPhase('failed')
    }
  }

  async function connectPeer(requestedCode = peerCode) {
    if (peerConnectInFlightRef.current || (mode === 'peer' && isPeerConnectActivePhase(phase))) return
    const normalized = normalizeCode(requestedCode)
    setPeerCode(normalized)
    const validation = validatePeerCode(normalized, localCode, invalidatedCodes)
    if (validation === 'invalid') {
      setFailure(t('errorInvalidCode'))
      setConnectionPhase('failed')
      modeRef.current = 'peer'
      setMode('peer')
      return
    }
    if (validation === 'self') {
      setFailure(t('errorSelfCode'))
      setConnectionPhase('failed')
      modeRef.current = 'peer'
      setMode('peer')
      return
    }
    if (validation === 'refreshed') {
      setFailure(t('errorRefreshedCode'))
      setConnectionPhase('failed')
      modeRef.current = 'peer'
      setMode('peer')
      return
    }

    peerConnectInFlightRef.current = true
    setPeerConnectLoading(true)
    modeRef.current = 'peer'
    setMode('peer')
    clearLocalRoomRetryTimer()
    createRoomRequestRef.current += 1
    setFailure('')
    setActiveCode(normalized)
    const wasIncomingPairingCode = incomingPairingCode === normalized
    if (wasIncomingPairingCode) setIncomingPairingCode(null)
    setPeerDeviceLabel('')
    peerDeviceIdRef.current = ''
    setConnectionPathState('unknown')
    setConnectionPhase('checking-network')
    try {
      replaceSignaling(null)
      await closeActiveRoom()
      const roomKey = await deriveRoomKey(normalized)
      setConnectionPhase('checking-code')
      const joined = await joinRoom(roomKey, localDevice, readRejoinSessionToken(normalized))
      rememberRejoinSessionToken(normalized, joined.sessionToken)
      setActiveRoom(joined)
      setPairingDeadline(Date.parse(joined.pairingExpiresAt))
      pairingCountdownPausedAtRef.current = Date.now()
      void updatePeerDeviceLabel(joined)
      transferLimitsRef.current = {
        maxFileSize: joined.limits.maxFileSize,
        chunkSize: joined.limits.chunkSize,
      }
      setConnectionPhase(joined.peerCount >= 2 ? 'connecting' : 'waiting')
      let activeSession: SignalingSession | null = null
      const pendingMessages: SignalingMessage[] = []
      const session = await openSignaling(joined, (message) => {
        if (activeSession) void handleSignalMessage(message, activeSession)
        else pendingMessages.push(message)
      }, handleSignalingDisconnect)
      activeSession = session
      pendingMessages.splice(0).forEach((message) => void handleSignalMessage(message, session))
      replaceSignaling(session)
    } catch (error) {
      const reason = errorMessage(error, t('errorConnectionFailed'))
      setFailure(reason)
      setConnectionPhase('failed')
      if (shouldCreateLocalRoomAfterPeerFailure(error, wasIncomingPairingCode)) {
        scannedPairingLinkRef.current = false
        void createLocalRoom('initial')
      }
    } finally {
      peerConnectInFlightRef.current = false
      setPeerConnectLoading(false)
    }
  }

  function disconnect() {
    peerConnectInFlightRef.current = false
    resetConnectionFormAfterExit()
    replaceSignaling(null)
    replacePeerRuntime(null, { status: 'canceled' })
    resetTransfer()
    refreshLocalRoomAfterConnectionExit('active_disconnect')
  }

  function resetConnectionFormAfterExit() {
    const next = disconnectedConnectionFormState()
    modeRef.current = next.mode
    setMode(next.mode)
    setPeerCode(next.peerCode)
    setPeerConnectLoading(next.peerConnectLoading)
  }

  function onFilesSelected(files?: FileList | File[] | null) {
    const selected = Array.from(files || [])
    if (selected.length === 0) return
    try {
      if (!transferSessionRef.current) throw new Error('File channel is not ready.')
      const offers = transferSessionRef.current.publishOffers(selected)
      offers.forEach((offer, index) => retainedLocalFilesRef.current.set(offer.fileId, selected[index]))
    } catch (error) {
      const reason = errorMessage(error, t('errorFilePublishFailed'))
      setTransferStatus('failed')
      setTransferFailure(reason)
    }
  }

  function handleSendText(text: string) {
    if (isTextTooLarge(text)) {
      const stamp = new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')
      onFilesSelected([new File([text], `message-${stamp}.txt`, { type: 'text/plain' })])
      return
    }
    try {
      if (!transferSessionRef.current) throw new Error('File channel is not ready.')
      transferSessionRef.current.sendText(text)
    } catch (error) {
      setTransferFailure(errorMessage(error, t('errorFilePublishFailed')))
    }
  }

  function onDownload(fileId: string): boolean {
    try {
      const received = receivedFiles.find((file) => file.offer.fileId === fileId)
      if (received) {
        triggerBrowserDownload(received)
        return true
      }
      const session = transferSessionRef.current
      if (!session) throw new Error('File channel is not ready.')
      requestedDownloadFileIdsRef.current.add(fileId)
      session.acceptRemoteOffer(fileId)
      return true
    } catch (error) {
      requestedDownloadFileIdsRef.current.delete(fileId)
      setTransferStatus('failed')
      const reason = errorMessage(error, t('errorDownloadStartFailed'))
      setTransferFailure(reason)
      return false
    }
  }

  function onCancelTransfer(fileId: string) {
    transferSessionRef.current?.cancelTransfer(fileId, 'user_cancel')
  }

  function onRecallText(messageId: string) {
    transferSessionRef.current?.recall('text', messageId)
  }

  function onRecallFile(fileId: string) {
    transferSessionRef.current?.recall('file', fileId)
  }

  async function resolvePeerDeviceId(): Promise<string> {
    const activeRoom = activeRoomRef.current
    if (!activeRoom) return ''
    try {
      const status = await getRoomStatus(activeRoom)
      const peer = status.peers.find((item) => item.deviceId !== activeRoom.deviceId)
      if (peer?.deviceLabel) setPeerDeviceLabel(peer.deviceLabel)
      return peer?.deviceId || ''
    } catch {
      return ''
    }
  }

  function triggerBrowserDownload(file: ReceivedFile) {
    browserDownloadUrlsRef.current.add(file.url)
    const anchor = downloadAnchorRef.current || document.createElement('a')
    downloadAnchorRef.current = anchor
    anchor.href = file.url
    anchor.download = file.offer.name
    anchor.rel = 'noopener'
    anchor.style.display = 'none'
    if (!anchor.isConnected) document.body.appendChild(anchor)
    anchor.click()
  }

  const paired = phase === 'connected'
  // A sheet held open across a disconnect would reappear over the next pairing.
  useEffect(() => {
    if (!paired) setDetailsOpen(false)
  }, [paired])
  const stepsVisible = mode === 'peer'
  const direction = getDirection(mode, phase, t)
  const steps = getSteps(phase)
  const countdownText = `${String(Math.floor(countdown / 60)).padStart(2, '0')}:${String(countdown % 60).padStart(2, '0')}`
  const peerConnectBusy = isPeerConnectBusy(mode, phase, peerConnectLoading)
  const connectionActionsLocked = shouldLockConnectionActions(mode, phase, peerConnectLoading)
  const peerConnectDisabled = peerCode.length !== 8 || peerConnectBusy || connectionActionsLocked
  const transferActionsLocked = shouldLockTransferStartActions(transferStatus, localFiles, remoteFiles)
  const pairingQrDataUrl = shouldDisplayPairingQr(localCode, pairingQr) ? pairingQr.dataUrl : ''

  return (
    <div className={(paired ? 'page is-paired' : 'page is-unpaired') + (detailsOpen ? ' details-open' : '')}>
      {paired && detailsOpen && (
        <div className="sheet-scrim" onClick={() => setDetailsOpen(false)} aria-hidden="true" />
      )}
      <header className="topbar">
        <div className="brand">
          <img src={siteConfig.iconUrl} alt="" />
          <div>
            <strong>{siteConfig.siteName || t('appName')}</strong>
            <span>{t('tagline')}</span>
          </div>
        </div>
        <div className="top-actions">
          <span className="status-pill">{phaseLabel(phase, t)}</span>
          <a
            className={`button-link${connectionActionsLocked ? ' is-disabled' : ''}`}
            aria-label={googlePlayDownload.label}
            href={googlePlayDownload.url}
            target={googlePlayDownload.target}
            rel={googlePlayDownload.rel}
            aria-disabled={connectionActionsLocked}
            tabIndex={connectionActionsLocked ? -1 : undefined}
            onClick={(event) => {
              if (connectionActionsLocked) event.preventDefault()
            }}
          >
            <Download size={18} aria-hidden="true" />
            <span>{googlePlayDownload.label}</span>
          </a>
          <ThemeToggle locale={locale} />
          <div className="language-menu" ref={languageMenuRef}>
            <button
              className="language-trigger"
              type="button"
              aria-haspopup="listbox"
              aria-expanded={languageMenuOpen}
              aria-label={t('switchLanguage')}
              disabled={connectionActionsLocked}
              onClick={() => setLanguageMenuOpen((open) => !open)}
            >
              <Globe2 size={18} />
              <span>{localeLabels[locale]}</span>
              <ChevronDown size={16} />
            </button>
            {languageMenuOpen && (
              <div className="language-popover" role="listbox" aria-label={t('switchLanguage')}>
                {supportedLocales.map((item) => (
                  <button
                    key={item}
                    className={item === locale ? 'is-selected' : ''}
                    type="button"
                    role="option"
                    aria-selected={item === locale}
                    disabled={connectionActionsLocked}
                    onClick={() => changeLocale(item)}
                  >
                    <span>{localeLabels[item]}</span>
                    {item === locale ? <Check size={16} /> : null}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </header>

      <main className="layout">
        <section className="connection-area" aria-label={t('connectionArea')}>
          {!paired && (
            <div className="connection-cards">
              <article className="panel self-card">
                <div className="panel-heading">
                  <h2>{t('selfCard')}</h2>
                </div>
                <span className="field-label">{t('localCode')}</span>
                <div className="local-code-row">
                  <strong className="code-display">{localCode}</strong>
                  <span className={`ttl-badge${countdown <= 30 ? ' is-urgent' : ''}`}>{`${t('security')} ${countdownText}`}</span>
                </div>
                <p className="muted">{t('selfCodeHint')}</p>
                <div className="pairing-qr" aria-label={t('pairingQrLabel')}>
                  <div className="pairing-qr-code">
                    {pairingQrDataUrl ? (
                      <img src={pairingQrDataUrl} alt={t('pairingQrAlt')} />
                    ) : (
                      <LoaderCircle className="spin-icon" size={24} aria-hidden="true" />
                    )}
                  </div>
                  <div>
                    <strong>{t('pairingQrTitle')}</strong>
                    <p className="muted">{t('pairingQrHint')}</p>
                  </div>
                </div>
                <div className="button-row">
                  <button className="primary" type="button" onClick={() => refreshLocalCode('manual')} disabled={connectionActionsLocked}>
                    <RefreshCw size={16} />
                    {t('refreshCode')}
                  </button>
                  <button className="copy-code-button" type="button" onClick={() => void copyLocalCode()} aria-label={t('copyCode')} disabled={!localCode || connectionActionsLocked}>
                    {codeCopied ? <Check size={16} /> : <Copy size={16} />}
                    <span>{codeCopied ? t('copied') : t('copyCode')}</span>
                  </button>
                </div>
              </article>

              <article className="panel peer-card">
                <div className="panel-heading">
                  <h2>{t('connectCard')}</h2>
                  <span className="mini">{t('eightDigits')}</span>
                </div>
                <label className="field-label" htmlFor="peerCode">{t('peerCode')}</label>
                <div className="join-row">
                  <input
                    id="peerCode"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="\d{8}"
                    aria-describedby="peerCodeHint"
                    value={peerCode}
                    maxLength={8}
                    placeholder={t('peerCodePlaceholder')}
                    disabled={connectionActionsLocked}
                    onChange={(event) => setPeerCode(normalizeCode(event.target.value))}
                    onKeyDown={(event) => {
                      if (connectionActionsLocked) {
                        event.preventDefault()
                        return
                      }
                      if (event.key === 'Enter') void connectPeer()
                    }}
                  />
                  <button
                    className="primary connect-button"
                    type="button"
                    onClick={() => void connectPeer()}
                    disabled={peerConnectDisabled}
                    aria-busy={peerConnectBusy}
                  >
                    {peerConnectBusy && <LoaderCircle className="spin-icon" size={16} aria-hidden="true" />}
                    <span>{peerConnectBusy ? t('phaseConnecting') : t('connectAction')}</span>
                  </button>
                </div>
                <p className="muted" id="peerCodeHint">{t('peerCodeHint')}</p>
                <ConnectionStatus
                  t={t}
                  direction={direction}
                  localDevice={localDevice.label}
                  peerDevice={peerDeviceLabel || t('wait')}
                  visibleSteps={stepsVisible}
                  steps={steps}
                  failure={failure}
                />
              </article>
            </div>
          )}

          {paired && (
            <article className="panel summary-card session-card">
              <div>
                <span className="field-label">{t('pairedCode')}</span>
                <strong className="summary-code">{activeCode}</strong>
                <p className="muted">{t('pairedHint')}</p>
                <div className="device-grid paired-devices">
                  <div><span>{t('local')}</span><strong>{localDevice.label}</strong></div>
                  <div><span>{t('peer')}</span><strong>{peerDeviceLabel || t('peerBrowser')}</strong></div>
                </div>
              </div>
              <button className="disconnect-button" type="button" onClick={disconnect} disabled={transferActionsLocked}>{t('disconnect')}</button>
            </article>
          )}
          {paired && (
            <DiagnosticsPanel t={t} phase={phase} paired={paired} dataChannelOpen={dataChannelOpen} connectionPath={connectionPath} failure={failure} />
          )}
        </section>

        {paired && (
          <TransferPanel
            t={t}
            localFiles={localFiles}
            remoteFiles={remoteFiles}
            receivedFiles={receivedFiles}
            messages={peerMessages}
            peerName={peerDeviceLabel || t('peerBrowser')}
            localName={localDevice.label}
            transferFailure={transferFailure}
            connectionPath={connectionPath}
            actionsLocked={transferActionsLocked}
            detailsOpen={detailsOpen}
            onOpenDetails={() => setDetailsOpen(true)}
            onFilesSelected={onFilesSelected}
            onSendText={handleSendText}
            onDownload={onDownload}
            onCancel={onCancelTransfer}
            onRecallText={onRecallText}
            onRecallFile={onRecallFile}
          />
        )}

        {!paired && (
          <DiagnosticsPanel t={t} phase={phase} paired={paired} dataChannelOpen={dataChannelOpen} connectionPath={connectionPath} failure={failure} />
        )}

        {!paired && (
          <section className="panel homepage-intro" aria-label={t('homepageHeading')}>
            <h1>{t('homepageHeading')}</h1>
            <p className="muted">{t('tagline')}</p>
            <div className="homepage-how">
              <h2>{t('howItWorksTitle')}</h2>
              <ol>
                <li>{t('howItWorksStep1')}</li>
                <li>{t('howItWorksStep2')}</li>
                <li>{t('howItWorksStep3')}</li>
              </ol>
              <p className="muted">{t('howItWorksLimits')}</p>
              <p className="homepage-links">
                <a href="https://lan-share.com/faq">{t('howItWorksFaqLink')}</a>
                <a href="https://lan-share.com/compatibility">{t('howItWorksCompatibilityLink')}</a>
              </p>
            </div>
          </section>
        )}
      </main>

      {shouldShowDirectFailureDialog(phase, directFailureNotice) && (
        <DirectFailureDialog t={t} failure={directFailureNotice} onClose={() => setDirectFailureNotice('')} />
      )}

      <SiteFooter siteConfig={siteConfig} locale={locale} localDevice={localDevice} footerRef={(node) => { footerRef.current = node }} />
    </div>
  )
}

function ConnectionStatus({
  t,
  direction,
  localDevice,
  peerDevice,
  visibleSteps,
  steps,
  failure,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  direction: string
  localDevice: string
  peerDevice: string
  visibleSteps: boolean
  steps: Record<string, StepState>
  failure: string
}) {
  return (
    <section className="status-card" aria-label={t('connectionStatus')}>
      <div className="status-heading">
        <h3>{t('connectionStatus')}</h3>
        <span className="mini">{direction}</span>
      </div>
      <div className="device-grid">
        <div><span>{t('local')}</span><strong>{localDevice}</strong></div>
        <div><span>{t('peer')}</span><strong>{peerDevice}</strong></div>
      </div>
      {visibleSteps && (
        <ol className="steps">
          <Step label={t('stageNetwork')} value={steps.network} t={t} />
          <Step label={t('stageCode')} value={steps.code} t={t} />
          <Step label={t('stagePairing')} value={steps.pairing} t={t} />
          <Step label={t('stageResult')} value={steps.result} t={t} />
        </ol>
      )}
      {!visibleSteps && <p className="muted">{t('hiddenStepsHint')}</p>}
      {failure && <div className="error-banner">{failure}</div>}
    </section>
  )
}

function Step({ label, value, t }: { label: string; value: StepState; t: (key: Parameters<typeof translate>[1]) => string }) {
  return (
    <li className={`step ${value}`}>
      <span>{label}</span>
      <strong>{t(value)}</strong>
    </li>
  )
}

/**
 * A short, dependency-free avatar label: "PC Chrome" -> "PC", "手机" -> "手".
 */
export function deviceBadge(label: string): string {
  const words = String(label || '').trim().split(/[\s_-]+/).filter(Boolean)
  if (words.length === 0) return '?'
  const ascii = words.filter((word) => /^[\x20-\x7e]+$/.test(word))
  if (ascii.length >= 2) return `${ascii[0][0]}${ascii[1][0]}`.toUpperCase()
  if (ascii.length === 1) return ascii[0].slice(0, 2).toUpperCase()
  return words[0].slice(0, 1)
}

export type ChatTimelineEntry =
  | { kind: 'text'; key: string; atMs: number; message: PeerMessageItem }
  | { kind: 'file'; key: string; atMs: number; file: TransferFileItem; incoming: boolean }
  | { kind: 'recalled'; key: string; atMs: number; byMe: boolean }

/**
 * One chronological conversation: text messages and file bubbles share a single
 * ordered list, so a message sent after a file never lands above that file.
 * Recalled entries stay in place as a system note.
 */
export function buildChatTimeline(
  messages: PeerMessageItem[],
  localFiles: TransferFileItem[],
  remoteFiles: TransferFileItem[],
): ChatTimelineEntry[] {
  const entries: ChatTimelineEntry[] = []
  messages.forEach((message) => {
    if (message.recalled) {
      entries.push({ kind: 'recalled', key: `recalled-text-${message.id}`, atMs: message.atMs, byMe: message.direction === 'outgoing' })
      return
    }
    entries.push({ kind: 'text', key: `text-${message.id}`, atMs: message.atMs, message })
  })
  const pushFile = (file: TransferFileItem, incoming: boolean) => {
    if (file.recalled) {
      entries.push({ kind: 'recalled', key: `recalled-file-${file.offer.fileId}`, atMs: file.atMs, byMe: !incoming })
      return
    }
    entries.push({ kind: 'file', key: `${incoming ? 'remote' : 'local'}-${file.offer.fileId}`, atMs: file.atMs, file, incoming })
  }
  localFiles.forEach((file) => pushFile(file, false))
  remoteFiles.forEach((file) => pushFile(file, true))
  return entries.sort((left, right) => left.atMs - right.atMs)
}

function TransferPanel({
  t,
  localFiles,
  remoteFiles,
  receivedFiles,
  messages,
  peerName,
  localName,
  transferFailure,
  connectionPath,
  actionsLocked,
  detailsOpen,
  onOpenDetails,
  onFilesSelected,
  onSendText,
  onDownload,
  onCancel,
  onRecallText,
  onRecallFile,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  localFiles: TransferFileItem[]
  remoteFiles: TransferFileItem[]
  receivedFiles: ReceivedFile[]
  messages: PeerMessageItem[]
  peerName: string
  localName: string
  transferFailure: string
  connectionPath: ConnectionPath
  actionsLocked: boolean
  detailsOpen: boolean
  onOpenDetails: () => void
  onFilesSelected: (files?: FileList | File[] | null) => void
  onSendText: (text: string) => void
  onDownload: (fileId: string) => boolean
  onCancel: (fileId: string) => void
  onRecallText: (messageId: string) => void
  onRecallFile: (fileId: string) => void
}) {
  const [draft, setDraft] = useState('')
  const [emojiOpen, setEmojiOpen] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const [folderNotice, setFolderNotice] = useState('')
  const inputRef = useRef<HTMLInputElement | null>(null)
  const receivedById = new Map(receivedFiles.map((file) => [file.offer.fileId, file]))
  const timeline = buildChatTimeline(messages, localFiles, remoteFiles)
  const streamRef = useRef<HTMLDivElement | null>(null)
  const timelineLength = timeline.length
  useEffect(() => {
    // Keep the newest entry in view, the way any chat does.
    const stream = streamRef.current
    if (!stream) return
    stream.scrollTop = stream.scrollHeight
  }, [timelineLength])
  const fileCapNotice = (truncated: boolean) => (
    truncated ? t('folderTooMany').replace('{count}', String(MAX_FOLDER_FILES)) : ''
  )
  const handleFileInputChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    if (actionsLocked) {
      event.target.value = ''
      return
    }
    const files = Array.from(event.target.files || [])
    event.target.value = ''
    const { accepted, truncated } = limitSelectedFiles(files)
    setFolderNotice(fileCapNotice(truncated))
    onFilesSelected(accepted)
  }
  const useSplitAndroidPickers = shouldSplitAndroidFilePickers(typeof navigator === 'undefined' ? '' : navigator.userAgent)
  const dropZoneHandlers = {
    onDragEnter: (event: React.DragEvent<HTMLElement>) => {
      event.preventDefault()
      if (!actionsLocked) setDragActive(true)
    },
    onDragOver: (event: React.DragEvent<HTMLElement>) => event.preventDefault(),
    onDragLeave: (event: React.DragEvent<HTMLElement>) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragActive(false)
    },
    onDrop: (event: React.DragEvent<HTMLElement>) => {
      event.preventDefault()
      setDragActive(false)
      if (actionsLocked) return
      // Capture entries before the DataTransfer goes inert, then expand folders.
      const entries = droppedEntries(event.dataTransfer)
      if (!hasDroppedDirectory(entries)) {
        const { accepted, truncated } = limitSelectedFiles(Array.from(event.dataTransfer.files || []))
        setFolderNotice(fileCapNotice(truncated))
        onFilesSelected(accepted)
        return
      }
      setFolderNotice('')
      void collectDroppedFiles(entries).then(({ files, truncated }) => {
        if (files.length === 0) {
          setFolderNotice(t('folderEmpty'))
          return
        }
        setFolderNotice(fileCapNotice(truncated))
        onFilesSelected(files)
      })
    },
  }

  const submit = () => {
    const text = draft.trim()
    if (!text || actionsLocked) return
    onSendText(text)
    setDraft('')
    setEmojiOpen(false)
  }
  const insertEmoji = (emoji: string) => {
    setDraft((current) => current + emoji)
    inputRef.current?.focus()
  }

  return (
    <section className="panel transfer-panel chat-window" data-testid="transfer-panel">
      <header className="chat-titlebar">
        <h2 className="chat-peer-name" title={peerName}>{peerName}</h2>
        <span className="path-badge" title={t('connectionPath')}>{connectionPathLabel(connectionPath, t)}</span>
        <button
          type="button"
          className="chat-details-trigger"
          aria-label={t('connectionDetails')}
          title={t('connectionDetails')}
          aria-expanded={detailsOpen}
          onClick={onOpenDetails}
        >
          <Info size={18} aria-hidden="true" />
          <span>{t('connectionDetails')}</span>
        </button>
      </header>

      <div className={`chat-stream drop-zone${dragActive ? ' is-drag-over' : ''}`} ref={streamRef} {...dropZoneHandlers}>
        {dragActive && <div className="chat-drop-hint">{t('dropToSend')}</div>}
        {timeline.length === 0 ? (
          <div className="chat-empty">
            <span>{t('messagesEmpty')}</span>
            <span className="chat-empty-hint">{t('dropFile')}</span>
          </div>
        ) : (
          <div className="chat-timeline">
            {timeline.map((entry, index) => {
              if (entry.kind === 'recalled') {
                return (
                  <div key={entry.key} className="chat-system">
                    {entry.byMe ? t('recallSelf') : t('recallPeer')}
                  </div>
                )
              }
              const incoming = entry.kind === 'file' ? entry.incoming : entry.message.direction === 'incoming'
              const previous = timeline[index - 1]
              const previousIncoming = previous && previous.kind !== 'recalled'
                ? (previous.kind === 'file' ? previous.incoming : previous.message.direction === 'incoming')
                : null
              return (
                <div key={entry.key} className={`chat-row ${incoming ? 'is-incoming' : 'is-outgoing'}`}>
                  <span className={`chat-avatar ${incoming ? 'is-peer' : 'is-local'}`} aria-hidden="true">
                    {deviceBadge(incoming ? peerName : localName)}
                  </span>
                  <div className="chat-entry">
                    {incoming && previousIncoming !== true && <span className="chat-sender">{peerName}</span>}
                    {entry.kind === 'text' ? (
                      <MessageBubble
                        t={t}
                        message={entry.message}
                        onRecall={incoming || actionsLocked ? undefined : () => onRecallText(entry.message.id)}
                      />
                    ) : (
                      <FileList
                        heading={false}
                        variant={entry.incoming ? 'incoming' : 'outgoing'}
                        title={entry.incoming ? t('peerFiles') : t('localFiles')}
                        emptyText={entry.incoming ? t('waitingPeerFile') : t('noFile')}
                        files={[entry.file]}
                        receivedById={entry.incoming ? receivedById : undefined}
                        t={t}
                        actionsLocked={actionsLocked}
                        onDownload={entry.incoming ? onDownload : undefined}
                        onCancel={entry.incoming ? onCancel : undefined}
                        onRecall={entry.incoming || actionsLocked ? undefined : onRecallFile}
                      />
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        )}
        {transferFailure && <div className="error-banner">{transferFailure}</div>}
      </div>

      <div className="chat-composer">
        {emojiOpen && <EmojiPicker label={t('emojiPickerLabel')} onPick={insertEmoji} />}
        {folderNotice && <p className="chat-folder-notice" role="status">{folderNotice}</p>}
        <div className="chat-composer-row">
          <div className="chat-composer-tools">
            <button
              type="button"
              className="chat-tool-button"
              aria-label={t('emojiPickerLabel')}
              aria-expanded={emojiOpen}
              disabled={actionsLocked}
              onClick={() => setEmojiOpen((open) => !open)}
            >
              <Smile size={22} aria-hidden="true" />
            </button>
            {useSplitAndroidPickers ? (
              <>
                <label className={`chat-tool-button file-picker-button${actionsLocked ? ' is-disabled' : ''}`} aria-disabled={actionsLocked} title={t('chooseFromFiles')}>
                  <FolderOpen size={22} aria-hidden="true" />
                  <input type="file" accept={androidDocumentFileAccept} multiple disabled={actionsLocked} aria-label={t('chooseFromFiles')} onChange={handleFileInputChange} />
                </label>
                <label className={`chat-tool-button file-picker-button${actionsLocked ? ' is-disabled' : ''}`} aria-disabled={actionsLocked} title={t('chooseFromGallery')}>
                  <ImageIcon size={22} aria-hidden="true" />
                  <input type="file" accept={androidGalleryFileAccept} multiple disabled={actionsLocked} aria-label={t('chooseFromGallery')} onChange={handleFileInputChange} />
                </label>
              </>
            ) : (
              <>
                <label className={`chat-tool-button file-picker-button${actionsLocked ? ' is-disabled' : ''}`} aria-disabled={actionsLocked} title={t('chooseFile')}>
                  <Plus size={22} aria-hidden="true" />
                  <input type="file" multiple disabled={actionsLocked} aria-label={t('chooseFile')} onChange={handleFileInputChange} />
                </label>
                <label className={`chat-tool-button file-picker-button${actionsLocked ? ' is-disabled' : ''}`} aria-disabled={actionsLocked} title={t('chooseFolder')}>
                  <FolderOpen size={22} aria-hidden="true" />
                  <input
                    type="file"
                    multiple
                    disabled={actionsLocked}
                    aria-label={t('chooseFolder')}
                    onChange={handleFileInputChange}
                    data-testid="folder-input"
                    // React exposes no typed prop for directory selection, so the
                    // attribute goes on the node itself.
                    ref={(node) => {
                      node?.setAttribute('webkitdirectory', '')
                    }}
                  />
                </label>
              </>
            )}
          </div>
          <form
            className="message-input-row"
            onSubmit={(event) => {
              event.preventDefault()
              submit()
            }}
          >
            <input
              ref={inputRef}
              type="text"
              value={draft}
              disabled={actionsLocked}
              placeholder={t('messagePlaceholder')}
              aria-label={t('messagePlaceholder')}
              onChange={(event) => setDraft(event.target.value)}
            />
            <button type="submit" className="primary" disabled={actionsLocked || !draft.trim()}>{t('sendMessage')}</button>
          </form>
        </div>
        <p className="muted chat-note">{t('peerVisible')}</p>
      </div>
    </section>
  )
}

// A short, dependency-free emoji palette keeps the chat lightweight: no picker
// library and no full Unicode table, just the reactions people actually send.
export const emojiOptions = [
  '😀', '😄', '😊', '🙂', '😉', '😍', '😘', '😜',
  '🤔', '😐', '😴', '😢', '😭', '😅', '🤣', '😇',
  '👍', '👎', '👏', '🙏', '💪', '🤝', '✌️', '👌',
  '❤️', '🎉', '🔥', '✨', '⭐', '✅', '❌', '📎',
  '📁', '📷', '🔗', '💡',
]

export function EmojiPicker({ label, onPick }: { label: string; onPick: (emoji: string) => void }) {
  return (
    <div className="emoji-picker" role="group" aria-label={label}>
      {emojiOptions.map((emoji) => (
        <button
          key={emoji}
          type="button"
          className="emoji-option"
          aria-label={emoji}
          onClick={() => onPick(emoji)}
        >
          {emoji}
        </button>
      ))}
    </div>
  )
}

export function MessageBubble({
  t,
  message,
  onRecall,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  message: PeerMessageItem
  onRecall?: () => void
}) {
  const [copied, setCopied] = useState(false)
  const copyMessage = async () => {
    try {
      await navigator.clipboard.writeText(message.text)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access is best-effort; the message text stays selectable.
    }
  }

  return (
    <div className={`message-bubble ${message.direction === 'outgoing' ? 'is-outgoing' : 'is-incoming'}`}>
      <span className="message-text">{message.text}</span>
      <button
        type="button"
        className="message-copy"
        onClick={() => void copyMessage()}
        aria-label={t('copyCode')}
      >
        {copied ? <Check size={14} /> : <Copy size={14} />}
      </button>
      {onRecall && (
        <button
          type="button"
          className="message-recall"
          onClick={onRecall}
          aria-label={t('recall')}
          title={t('recall')}
          data-testid="message-recall"
        >
          <Undo2 size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  )
}

export function MessageThread({
  t,
  messages,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  messages: PeerMessageItem[]
}) {
  if (messages.length === 0) {
    return <p className="muted chat-empty">{t('messagesEmpty')}</p>
  }

  return (
    <div className="message-thread" role="log" aria-label={t('messagesTitle')}>
      {messages.map((message) => <MessageBubble key={message.id} t={t} message={message} />)}
    </div>
  )
}

export function FileList({
  title,
  emptyText,
  files,
  receivedById,
  t,
  actionsLocked,
  variant,
  hideWhenEmpty,
  heading,
  onDownload,
  onCancel,
  onRecall,
}: {
  title: string
  emptyText: string
  files: TransferFileItem[]
  receivedById?: Map<string, ReceivedFile>
  t: (key: Parameters<typeof translate>[1]) => string
  actionsLocked: boolean
  // Chat mode tags each group as the sender's or the peer's and drops the empty
  // placeholder card so an idle conversation shows nothing but its hint.
  variant?: 'outgoing' | 'incoming'
  hideWhenEmpty?: boolean
  // The chat timeline renders one bubble per file and skips the group heading.
  heading?: boolean
  onDownload?: (fileId: string) => boolean
  onCancel?: (fileId: string) => void
  // Offered on a sender's own bubble instead of cancel: it retracts the file for
  // both sides, which also stops an in-flight transfer.
  onRecall?: (fileId: string) => void
}) {
  const [presentation, dispatchPresentation] = useReducer(fileListPresentationReducer, initialFileListPresentationState)
  const downloadRequestGuardRef = useRef(new Set<string>())
  const pendingDeleteRef = useRef<TransferFileItem | null>(null)
  const deleteTriggerRef = useRef<HTMLButtonElement | null>(null)
  const deleteCancelButtonRef = useRef<HTMLButtonElement | null>(null)
  const deleteConfirmButtonRef = useRef<HTMLButtonElement | null>(null)
  const fileListHeadingRef = useRef<HTMLDivElement | null>(null)
  const deleteDialogTitleId = useId()
  const fileStatesKey = files.map((item) => `${item.offer.fileId}:${item.status}`).join('\u0000')

  useEffect(() => {
    const fileStates = files.map((item) => ({ fileId: item.offer.fileId, status: item.status }))
    const fileIds = fileStates.map((item) => item.fileId)
    syncFileDownloadGuards(downloadRequestGuardRef.current, fileStates)
    if (pendingDeleteRef.current && !fileIds.includes(pendingDeleteRef.current.offer.fileId)) pendingDeleteRef.current = null
    dispatchPresentation({ type: 'sync-files', files: fileStates })
  }, [fileStatesKey])

  useEffect(() => {
    if (!presentation.pendingDelete) return
    deleteCancelButtonRef.current?.focus()
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      closeDeleteDialog()
    }
    document.addEventListener('keydown', closeOnEscape)
    return () => document.removeEventListener('keydown', closeOnEscape)
  }, [presentation.pendingDelete])

  const closeDeleteDialog = () => {
    pendingDeleteRef.current = null
    dispatchPresentation({ type: 'close-delete' })
    window.requestAnimationFrame(() => restoreFileListFocus(false, deleteTriggerRef.current, fileListHeadingRef.current))
  }

  const confirmDelete = () => {
    if (!onCancel) return
    confirmPendingFileDelete(pendingDeleteRef, dispatchPresentation, onCancel)
    deleteTriggerRef.current = null
    window.requestAnimationFrame(() => restoreFileListFocus(true, null, fileListHeadingRef.current))
  }

  if (hideWhenEmpty && files.length === 0) return null

  return (
    <div className={`file-list${variant ? ` is-${variant}` : ''}`}>
      {heading !== false && (
        <div className="file-list-heading" ref={fileListHeadingRef} tabIndex={-1}>
          <strong>{title}</strong>
          <div className="file-list-heading-actions">
            <span>{files.length}</span>
          </div>
        </div>
      )}
      {files.length === 0 && (
        <div className="file-card empty-file-card">
          <div className="empty-file-content">
            <strong>{emptyText}</strong>
            <span>{t('progressIdle')}</span>
          </div>
        </div>
      )}
      {files.map((item) => {
        const received = receivedById?.get(item.offer.fileId)
        const canDownload = !actionsLocked && !!onDownload && canDownloadPeerFile(item, !!received)
        const canCancel = !!onCancel && canCancelTransferFile(item)
        const downloadLabel = downloadButtonLabel(item.status, !!received, false, t)
        const requested = presentation.requestedDownloadIds.has(item.offer.fileId)
        const progressRequested = shouldShowFileProgress(item.status, requested)
        const badge = fileTypeBadge(item.offer.name, item.offer.mime)
        return (
          <div className={`file-card file-message${onDownload ? ' file-card-downloadable' : ''}`} data-testid={receivedById ? 'remote-file-card' : 'local-file-card'} key={item.offer.fileId}>
            <span className={`file-type-badge is-${badge.kind}`} aria-hidden="true">{badge.label}</span>
            <div className="file-body">
              <strong className="file-name" title={item.offer.name}>{item.offer.name}</strong>
              <span className="file-meta" title={item.failure || undefined}>
                <span dir="ltr">{formatBytes(item.offer.size)}</span> · {fileStatusLabel(item.status, t)}
              </span>
              {progressRequested && (
                <div
                  className="file-progress"
                  role="progressbar"
                  aria-label={formatFileProgressLabel(item.progress, t)}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={item.progress.percent}
                >
                  <span style={{ width: `${item.progress.percent}%` }} />
                </div>
              )}
            </div>
            {onDownload && (
              <button
                className="file-row-action file-download-button"
                type="button"
                onClick={() => requestFileDownload(item.offer.fileId, !!received, downloadRequestGuardRef.current, dispatchPresentation, onDownload)}
                disabled={!canDownload || (!received && requested)}
                aria-label={downloadLabel}
                title={downloadLabel}
              >
                <Download size={19} aria-hidden="true" />
              </button>
            )}
            {onRecall && (
              <button
                type="button"
                className="file-row-action file-recall-button"
                onClick={() => onRecall(item.offer.fileId)}
                aria-label={t('recall')}
                title={t('recall')}
                data-testid="file-recall"
              >
                <Undo2 size={19} aria-hidden="true" />
              </button>
            )}
            {!onRecall && onCancel && (
              <button
                type="button"
                className="file-row-action file-cancel-button"
                onClick={(event) => {
                  deleteTriggerRef.current = event.currentTarget
                  pendingDeleteRef.current = item
                  dispatchPresentation({ type: 'request-delete', file: item })
                }}
                disabled={!canCancel}
                aria-label={t('cancelTransfer')}
                title={t('cancelTransfer')}
              >
                <X size={19} aria-hidden="true" />
              </button>
            )}
          </div>
        )
      })}
      {presentation.pendingDelete && (
        <div
          className="file-delete-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeDeleteDialog()
          }}
        >
          <FileDeleteConfirmationDialog
            item={presentation.pendingDelete}
            t={t}
            titleId={deleteDialogTitleId}
            cancelButtonRef={deleteCancelButtonRef}
            confirmButtonRef={deleteConfirmButtonRef}
            onClose={closeDeleteDialog}
            onConfirm={confirmDelete}
          />
        </div>
      )}
    </div>
  )
}

export type FileListPresentationState = {
  requestedDownloadIds: Set<string>
  pendingDelete: TransferFileItem | null
}

export type FileListPresentationAction =
  | { type: 'request-download'; fileId: string }
  | { type: 'clear-download'; fileIds: string[] }
  | { type: 'request-delete'; file: TransferFileItem }
  | { type: 'close-delete' }
  | { type: 'sync-files'; files: Array<{ fileId: string; status: TransferStatus }> }

export const initialFileListPresentationState: FileListPresentationState = {
  requestedDownloadIds: new Set(),
  pendingDelete: null,
}

export function requestFileDownload(
  fileId: string,
  received: boolean,
  guardedIds: Set<string>,
  dispatch: (action: FileListPresentationAction) => void,
  onDownload: (fileId: string) => boolean,
): boolean {
  if (received) return onDownload(fileId)
  if (guardedIds.has(fileId)) return false
  guardedIds.add(fileId)
  dispatch({ type: 'request-download', fileId })
  const started = onDownload(fileId)
  if (!started) {
    guardedIds.delete(fileId)
    dispatch({ type: 'clear-download', fileIds: [fileId] })
  }
  return started
}

const settledTransferStatuses = new Set<TransferStatus>(['complete', 'failed', 'rejected', 'canceled'])

export function shouldShowFileProgress(status: TransferStatus, requested: boolean): boolean {
  if (settledTransferStatuses.has(status)) return false
  return status === 'transferring' || requested
}

export function syncFileDownloadGuards(
  guardedIds: Set<string>,
  files: Array<{ fileId: string; status: TransferStatus }>,
) {
  const activeIds = new Set(files.filter((item) => !settledTransferStatuses.has(item.status)).map((item) => item.fileId))
  for (const fileId of guardedIds) {
    if (!activeIds.has(fileId)) guardedIds.delete(fileId)
  }
}

export function confirmPendingFileDelete(
  pendingDelete: { current: TransferFileItem | null },
  dispatch: (action: FileListPresentationAction) => void,
  onCancel: (fileId: string) => void,
) {
  const item = pendingDelete.current
  if (!item) return
  pendingDelete.current = null
  dispatch({ type: 'close-delete' })
  onCancel(item.offer.fileId)
}

export function FileDeleteConfirmationDialog({
  item,
  t,
  titleId,
  cancelButtonRef,
  confirmButtonRef,
  onClose,
  onConfirm,
}: {
  item: TransferFileItem
  t: (key: Parameters<typeof translate>[1]) => string
  titleId: string
  cancelButtonRef: React.RefObject<HTMLButtonElement | null>
  confirmButtonRef: React.RefObject<HTMLButtonElement | null>
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <div
      className="file-delete-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onKeyDown={(event) => trapDeleteDialogTab(event, cancelButtonRef.current, confirmButtonRef.current)}
    >
      <h3 id={titleId}>{t('removeFileTitle')}</h3>
      <p>{t('removeFileMessage').replace('{filename}', item.offer.name)}</p>
      <div className="file-delete-actions">
        <button ref={cancelButtonRef} type="button" onClick={onClose}>{t('removeFileCancel')}</button>
        <button ref={confirmButtonRef} className="file-delete-confirm" type="button" onClick={onConfirm}>{t('removeFileConfirm')}</button>
      </div>
    </div>
  )
}

type Focusable = { focus: () => void } | null

export function restoreFileListFocus(confirmed: boolean, trigger: Focusable, heading: Focusable) {
  if (confirmed) heading?.focus()
  else (trigger || heading)?.focus()
}

export function trapDeleteDialogTab(
  event: { key: string; shiftKey: boolean; target: unknown; preventDefault: () => void },
  cancelButton: Focusable,
  confirmButton: Focusable,
) {
  if (event.key !== 'Tab' || !cancelButton || !confirmButton) return
  if (event.shiftKey && event.target === cancelButton) {
    event.preventDefault()
    confirmButton.focus()
  } else if (!event.shiftKey && event.target === confirmButton) {
    event.preventDefault()
    cancelButton.focus()
  }
}

export function fileListPresentationReducer(state: FileListPresentationState, action: FileListPresentationAction): FileListPresentationState {
  if (action.type === 'request-download') {
    if (state.requestedDownloadIds.has(action.fileId)) return state
    return { ...state, requestedDownloadIds: new Set([...state.requestedDownloadIds, action.fileId]) }
  }
  if (action.type === 'clear-download') {
    const requestedDownloadIds = new Set(state.requestedDownloadIds)
    action.fileIds.forEach((fileId) => requestedDownloadIds.delete(fileId))
    return requestedDownloadIds.size === state.requestedDownloadIds.size ? state : { ...state, requestedDownloadIds }
  }
  if (action.type === 'request-delete') return { ...state, pendingDelete: action.file }
  if (action.type === 'close-delete') return state.pendingDelete ? { ...state, pendingDelete: null } : state

  const currentIds = new Set(action.files.map((item) => item.fileId))
  const visibleProgressIds = new Set(action.files.filter((item) => !settledTransferStatuses.has(item.status)).map((item) => item.fileId))
  const requestedDownloadIds = new Set([...state.requestedDownloadIds].filter((fileId) => visibleProgressIds.has(fileId)))
  const pendingDelete = state.pendingDelete && currentIds.has(state.pendingDelete.offer.fileId) ? state.pendingDelete : null
  if (requestedDownloadIds.size === state.requestedDownloadIds.size && pendingDelete === state.pendingDelete) return state
  return { requestedDownloadIds, pendingDelete }
}

export function canDownloadPeerFile(item: Pick<TransferFileItem, 'status'>, received: boolean, requireReceived = false): boolean {
  if (requireReceived) return received && item.status === 'complete'
  if (item.status === 'transferring') return false
  return item.status === 'offered' || received
}

export function downloadButtonLabel(status: TransferStatus, received: boolean, requireManualDownload: boolean, t: (key: Parameters<typeof translate>[1]) => string): string {
  if (status === 'transferring') return t('downloading')
  if (received && !requireManualDownload) return t('downloaded')
  return t('downloadPeerFile')
}

export function shouldTriggerBrowserDownloadAfterReceive(fileId: string, requestedFileIds: ReadonlySet<string>): boolean {
  return requestedFileIds.has(fileId)
}

export function canCancelTransferFile(item: Pick<TransferFileItem, 'status'>): boolean {
  return item.status !== 'idle'
}

// WeChat-style file bubbles lead with a type badge. Without an icon library we
// derive a short extension label plus a color kind from the name and mime.
export type FileTypeBadge = { label: string; kind: string }

const fileBadgeKindsByExtension: Record<string, string> = {
  PNG: 'image', JPG: 'image', JPEG: 'image', GIF: 'image', WEBP: 'image', HEIC: 'image', BMP: 'image', SVG: 'image', AVIF: 'image',
  MP4: 'video', MOV: 'video', AVI: 'video', MKV: 'video', WEBM: 'video', M4V: 'video',
  MP3: 'audio', WAV: 'audio', FLAC: 'audio', M4A: 'audio', AAC: 'audio', OGG: 'audio',
  PDF: 'pdf',
  DOC: 'doc', DOCX: 'doc', PAGES: 'doc',
  XLS: 'sheet', XLSX: 'sheet', CSV: 'sheet', NUMBERS: 'sheet',
  PPT: 'slide', PPTX: 'slide', KEY: 'slide',
  ZIP: 'archive', RAR: 'archive', '7Z': 'archive', TAR: 'archive', GZ: 'archive', BZ2: 'archive', XZ: 'archive',
  APK: 'apk',
  TXT: 'text', MD: 'text', JSON: 'text', LOG: 'text',
}

export function fileTypeBadge(name: string, mime: string): FileTypeBadge {
  const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).trim().toUpperCase() : ''
  const mimeKind = mime.startsWith('image/') ? 'image'
    : mime.startsWith('video/') ? 'video'
      : mime.startsWith('audio/') ? 'audio'
        : ''
  const kind = fileBadgeKindsByExtension[extension] || mimeKind || 'file'
  const mimeSubtype = mime.includes('/') ? mime.split('/')[1].toUpperCase() : ''
  const fallbackLabel = mimeSubtype && mimeSubtype !== 'OCTET-STREAM' ? mimeSubtype : 'FILE'
  const label = (extension || fallbackLabel).slice(0, 4) || 'FILE'
  return { label, kind }
}

export function hasActiveFileTransfer(...fileGroups: Array<Array<Pick<TransferFileItem, 'status'>>>): boolean {
  return fileGroups.some((files) => files.some((item) => item.status === 'transferring'))
}

export function shouldLockTransferStartActions(transferStatus: TransferStatus, ...fileGroups: Array<Array<Pick<TransferFileItem, 'status'>>>): boolean {
  return transferStatus === 'transferring' || hasActiveFileTransfer(...fileGroups)
}

export function shouldSplitAndroidFilePickers(userAgent: string): boolean {
  return /\bAndroid\b/i.test(userAgent)
}

/** One folder can add at most this many files, so a huge tree cannot flood the chat. */
export const MAX_FOLDER_FILES = 100

/** Keep one selection inside the file cap and report whether anything was left out. */
export function limitSelectedFiles(files: File[], limit = MAX_FOLDER_FILES): { accepted: File[]; truncated: boolean } {
  if (files.length <= limit) return { accepted: files, truncated: false }
  return { accepted: files.slice(0, limit), truncated: true }
}

/**
 * The slice of the FileSystem entry API a drop walk needs. Declared structurally
 * so tests can drive the walk with plain objects instead of a browser.
 */
export interface DroppedEntry {
  isFile: boolean
  isDirectory: boolean
  file?: (onSuccess: (file: File) => void, onError?: (error: unknown) => void) => void
  createReader?: () => {
    readEntries: (
      onSuccess: (entries: DroppedEntry[]) => void,
      onError?: (error: unknown) => void,
    ) => void
  }
}

/**
 * The dropped items themselves, read synchronously: a DataTransfer goes inert
 * once the drop handler returns, so the entries must be captured right away.
 * Browsers without `webkitGetAsEntry` yield none, and the caller then falls
 * back to `dataTransfer.files`.
 */
export function droppedEntries(dataTransfer: DataTransfer): DroppedEntry[] {
  const entries: DroppedEntry[] = []
  for (const item of Array.from(dataTransfer.items || [])) {
    if (typeof item.webkitGetAsEntry !== 'function') continue
    const entry = item.webkitGetAsEntry()
    if (entry) entries.push(entry as unknown as DroppedEntry)
  }
  return entries
}

export function hasDroppedDirectory(entries: DroppedEntry[]): boolean {
  return entries.some((entry) => entry.isDirectory)
}

/**
 * Every file inside the dropped entries, folders walked depth-first, capped at
 * `limit`. A dropped folder means "send what is inside it": the flat file list
 * is what the transfer protocol carries, and names keep their own base name.
 */
export async function collectDroppedFiles(
  entries: DroppedEntry[],
  limit = MAX_FOLDER_FILES,
): Promise<{ files: File[]; truncated: boolean }> {
  const files: File[] = []
  const queue = [...entries]
  let truncated = false
  for (let index = 0; index < queue.length; index += 1) {
    const entry = queue[index]
    if (entry.isFile) {
      if (files.length >= limit) {
        truncated = true
        break
      }
      const file = await readEntryFile(entry)
      if (file) files.push(file)
      continue
    }
    if (!entry.isDirectory) continue
    const reader = entry.createReader?.()
    if (!reader) continue
    // readEntries() answers one batch at a time and signals the end with an
    // empty batch, so a folder of any size needs repeated reads.
    for (;;) {
      const batch = await readDirectoryBatch(reader)
      if (batch.length === 0) break
      queue.push(...batch)
    }
  }
  return { files, truncated }
}

function readEntryFile(entry: DroppedEntry): Promise<File | null> {
  return new Promise((resolve) => {
    if (!entry.file) {
      resolve(null)
      return
    }
    entry.file((file) => resolve(file), () => resolve(null))
  })
}

function readDirectoryBatch(reader: NonNullable<ReturnType<NonNullable<DroppedEntry['createReader']>>>): Promise<DroppedEntry[]> {
  return new Promise((resolve) => {
    reader.readEntries((entries) => resolve(entries), () => resolve([]))
  })
}

export const androidDocumentFileAccept = [
  'application/*',
  'text/*',
  '.pdf',
  '.zip',
  '.rar',
  '.7z',
  '.tar',
  '.gz',
  '.txt',
  '.md',
  '.csv',
  '.json',
  '.doc',
  '.docx',
  '.xls',
  '.xlsx',
  '.ppt',
  '.pptx',
  '.apk',
  '.bin',
].join(',')

export const androidGalleryFileAccept = 'image/*,video/*'

function upsertTransferItem(items: TransferFileItem[], next: TransferFileItem): TransferFileItem[] {
  const index = items.findIndex((item) => item.offer.fileId === next.offer.fileId)
  if (index === -1) return [...items, next]
  return items.map((item, currentIndex) => currentIndex === index ? { ...item, ...next } : item)
}

function updateTransferItem(items: TransferFileItem[], fileId: string, patch: Partial<Pick<TransferFileItem, 'status' | 'progress' | 'failure'>>): TransferFileItem[] {
  return items.map((item) => item.offer.fileId === fileId ? { ...item, ...patch } : item)
}

function removeTransferItem(items: TransferFileItem[], fileId: string): TransferFileItem[] {
  return items.filter((item) => item.offer.fileId !== fileId)
}

function upsertReceivedFile<T extends ReceivedFile>(files: T[], next: T): T[] {
  return [...files.filter((file) => file.offer.fileId !== next.offer.fileId), next]
}

function formatFileMeta(offer: FileOffer, status: TransferStatus, t: (key: Parameters<typeof translate>[1]) => string): string {
  return `${formatBytes(offer.size)} · ${offer.totalChunks} ${t('chunks')} · ${fileStatusLabel(status, t)}`
}

function formatFileProgressLabel(progress: TransferProgress, t: (key: Parameters<typeof translate>[1]) => string): string {
  return `${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)} · ${formatBytes(progress.bytesPerSecond)}/s · ${formatRemaining(progress.secondsRemaining, t)}`
}

export function DirectFailureDialog({
  t,
  failure,
  onClose,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  failure: string
  onClose: () => void
}) {
  return (
    <div className="modal-backdrop" role="presentation">
      <section className="direct-failure-modal" role="dialog" aria-modal="true" aria-labelledby="directFailureTitle">
        <div className="direct-failure-heading">
          <div>
            <span className="field-label">{t('connectionPath')}</span>
            <h2 id="directFailureTitle">{t('directConnectionFailed')}</h2>
          </div>
          <button className="direct-failure-close" type="button" aria-label={t('closeDialog')} onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </div>
        <p>{t('directConnectionFailureHint')}</p>
        <ul>
          {directFailureGuidanceSteps(t).map((step) => <li key={step}>{step}</li>)}
        </ul>
        {failure && <div className="error-banner">{failure}</div>}
      </section>
    </div>
  )
}

function DiagnosticsPanel({
  t,
  phase,
  paired,
  dataChannelOpen,
  connectionPath,
  failure,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  phase: Phase
  paired: boolean
  dataChannelOpen: boolean
  connectionPath: ConnectionPath
  failure: string
}) {
  return (
    <aside className="panel diagnostics">
      <div className="panel-heading">
        <h2>{t('diagnostics')}</h2>
      </div>
      <dl>
        <div><dt>{t('signal')}</dt><dd>{paired ? t('connected') : phaseLabel(phase, t)}</dd></div>
        <div><dt>{t('session')}</dt><dd>{paired ? t('connected') : t('notConnected')}</dd></div>
        <div><dt>{t('channel')}</dt><dd>{dataChannelOpen ? t('open') : t('notConnected')}</dd></div>
        <div><dt>{t('path')}</dt><dd>{paired ? connectionPathLabel(connectionPath, t) : t('unknown')}</dd></div>
        <div><dt>{t('failure')}</dt><dd>{failure || t('none')}</dd></div>
      </dl>
    </aside>
  )
}

function connectionPathLabel(path: ConnectionPath, t: (key: Parameters<typeof translate>[1]) => string): string {
  if (path === 'direct') return t('direct')
  return t('unknown')
}

// preferredActiveCode decides what the summary card shows when `peer_joined`
// arrives. A stale signaling closure can deliver a localCode captured before
// the room opened (possibly still empty, possibly the previous session's
// code), so an already-set code wins and the captured one only fills a gap.
// That keeps the card from blanking mid-connect and from resurrecting a code
// the current session already replaced.
export function preferredActiveCode(current: string, localCode: string): string {
  return current || localCode
}

export function validatePeerCode(peerCode: string, localCode: string, invalidatedCodes: string[]): PeerCodeValidationResult {
  if (!isUsableCode(peerCode)) return 'invalid'
  if (peerCode === localCode) return 'self'
  if (invalidatedCodes.includes(peerCode)) return 'refreshed'
  return 'ok'
}

export function buildPairingURL(currentHref: string, code: string, locale: Locale): string {
  const current = new URL(currentHref)
  const next = new URL('/', current.origin)
  next.pathname = explicitLocalePath('/', locale)
  next.searchParams.set(pairingCodeParam, normalizeCode(code))
  return next.toString()
}

export function pairingCodeFromURL(search: string): string | null {
  const code = normalizeCode(new URLSearchParams(search).get(pairingCodeParam) || '')
  return isUsableCode(code) ? code : null
}

export function safePairingURLPath(pathSearchHash: string): string {
  const current = new URL(pathSearchHash, 'https://lan-share.local')
  current.searchParams.delete(pairingCodeParam)
  const search = current.searchParams.toString()
  return `${current.pathname || '/'}${search ? `?${search}` : ''}${current.hash || ''}`
}

export function shouldDisplayPairingQr(localCode: string, pairingQr: PairingQrState): boolean {
  return localCode !== '' && pairingQr.code === localCode && pairingQr.dataUrl !== ''
}

// resolveInitialLocale picks the language a visit starts in: the URL (path or
// legacy query parameter), then the saved choice, then the first supported
// browser language, then the site's configured default. The configured default
// is only a last resort; it never overrides a language the browser already
// told us about.
export function resolveInitialLocale(storedLocale: string | null, pathname: string, search: string, browserLocales: readonly string[] = [], defaultLocale = 'en'): { locale: Locale; explicit: boolean; source: InitialLocaleSource } {
  const pathLocale = localeFromPath(pathname)
  if (pathLocale) return { locale: pathLocale, explicit: true, source: 'url' }
  const params = new URLSearchParams(search)
  const urlLocale = params.get('locale')
  if (urlLocale) return { locale: normalizeLocale(urlLocale), explicit: true, source: 'url' }
  if (storedLocale !== null) return { locale: normalizeLocale(storedLocale), explicit: true, source: 'stored' }
  const browserLocale = resolveBrowserLocale(browserLocales)
  if (browserLocale) return { locale: browserLocale, explicit: false, source: 'browser' }
  return { locale: normalizeLocale(defaultLocale), explicit: false, source: 'fallback' }
}

export function resolveBrowserLocale(browserLocales: readonly string[]): Locale | null {
  for (const candidate of browserLocales) {
    const locale = normalizeLocaleIfSupported(candidate)
    if (locale) return locale
  }
  return null
}

function normalizeLocaleIfSupported(locale: string | null | undefined): Locale | null {
  const value = String(locale || '').trim()
  if (!value) return null
  const normalized = normalizeLocale(value)
  if (normalized !== 'en') return normalized
  const lower = value.replace('_', '-').toLowerCase()
  return lower === 'en' || lower.startsWith('en-') ? 'en' : null
}

function browserLocaleCandidates(): string[] {
  const languages = Array.isArray(navigator.languages) ? navigator.languages.filter(Boolean) : []
  if (languages.length > 0) return languages
  return navigator.language ? [navigator.language] : []
}

function syncAutoLocalePath(source: InitialLocaleSource, locale: Locale) {
  if (source === 'url') return
  replaceLocalePath(locale, false)
}

// explicitLocalePath is the path an explicit language selection resolves to. English
// keeps an /en prefix so a link such as "/?locale=en" does not fall back to the
// visitor's saved language; canonical metadata keeps the unprefixed English path.
export function explicitLocalePath(pathname: string, locale: Locale): string {
  const base = stripLocalePath(pathname)
  const prefix = `/${locale}`
  if (base === '/') return prefix
  return `${prefix}${base}`
}

// replaceLocalePath rewrites the address bar to the locale path. An explicit selection
// (language menu) uses explicitLocalePath, while automatic detection keeps the English
// canonical unprefixed. Pages without authored content for the target locale keep the
// unprefixed path, matching the server's canonical rule for fallback content.
function replaceLocalePath(locale: Locale, overwriteExisting = true) {
  const current = new URL(window.location.href)
  if (!overwriteExisting && localeFromPath(current.pathname)) return
  const basePath = stripLocalePath(current.pathname)
  const nextPath = overwriteExisting ? explicitLocalePath(basePath, locale) : prefixedPath(basePath, locale)
  current.searchParams.delete('locale')
  window.history.replaceState(window.history.state, '', `${nextPath}${current.search}${current.hash}`)
}

function SiteFooter({ siteConfig, locale, localDevice, footerRef }: { siteConfig: PublicSiteConfig; locale: Locale; localDevice: ReturnType<typeof getLocalDevice>; footerRef?: (node: HTMLElement | null) => void }) {
  const t = (key: Parameters<typeof translate>[1]) => translate(locale, key)
  const footerLinks = siteConfig.footer.links.filter((link) => !isContactFooterLink(link))
  const footerMeta = [
    siteConfig.footer.developerName ? `${t('developer')}: ${siteConfig.footer.developerName}` : '',
    siteConfig.footer.contactEmail ? `${t('footerContact')}: ${siteConfig.footer.contactEmail}` : '',
  ].filter(Boolean).join(' · ')

  return (
    <footer className="site-footer" ref={footerRef}>
      <div>
        <strong>{siteConfig.footer.legalName || siteConfig.siteName}</strong>
        <p>{localizedFooterCopyright(siteConfig.footer.copyright, t)}</p>
        {footerMeta ? <p>{footerMeta}</p> : null}
      </div>
      <nav>
        {footerLinks.map((link) => (
          <a key={`${link.label}-${link.url}`} href={footerLinkHref(link, locale)}>
            {localizedFooterLinkLabel(link, t)}
          </a>
        ))}
      </nav>
    </footer>
  )
}

export function isContactFooterLink(link: { label: string; url: string }): boolean {
  const label = link.label.trim().toLowerCase()
  const url = link.url.trim().toLowerCase()
  return label === 'contact' && url.startsWith('mailto:')
}

function localizedFooterCopyright(value: string, t: (key: Parameters<typeof translate>[1]) => string): string {
  if (value.trim() === '' || value.trim() === defaultFooterCopyright) return t('footerCopyright')
  return value
}

export function localizedFooterLinkLabel(link: { label: string; url: string }, t: (key: Parameters<typeof translate>[1]) => string): string {
  const raw = link.url.trim().split(/[?#]/)[0]
  let path = raw
  try {
    path = new URL(raw, 'https://lan-share.com').pathname
  } catch {
    // Keep the raw value for unusual URLs; the label falls back to link.label.
  }
  path = path.replace(/\/+$/, '') || '/'
  if (path === '/privacy') return t('footerPrivacy')
  if (path === '/terms') return t('footerTerms')
  if (path === '/security') return t('footerSecurity')
  if (path === '/faq') return t('footerFaq')
  if (path === '/compatibility') return t('footerCompatibility')
  if (path === '/about') return t('footerAbout')
  return link.label
}

export function footerLinkHref(link: { label: string; url: string }, locale: Locale): string {
  const url = link.url.trim()
  if (!url.startsWith('/')) return url
  return prefixedPath(url, locale)
}

export function shouldAutoCreateLocalRoom(userAgent: string, webdriver: boolean, hasIncomingPairingCode = false): boolean {
  if (hasIncomingPairingCode) return false
  if (webdriver) return false
  return !/(chrome-lighthouse|lighthouse|pagespeed)/i.test(userAgent)
}

export function shouldCreateLocalRoomAfterPeerFailure(error: unknown, wasIncomingPairingCode: boolean): boolean {
  return wasIncomingPairingCode && error instanceof ApiError && error.code === 'expired_code'
}

export function canAutoJoinIncomingPairingCode(incomingPairingCode: string | null, peerConnectInFlight: boolean, mode: ConnectionMode, phase: Phase): boolean {
  return incomingPairingCode !== null && !peerConnectInFlight && !(mode === 'peer' && isPeerConnectActivePhase(phase))
}

export function isCurrentRoomRequest(requestRoom: CloseRoomRequest, activeRoom: CloseRoomRequest | null): boolean {
  return activeRoom !== null &&
    requestRoom.roomId === activeRoom.roomId &&
    requestRoom.deviceId === activeRoom.deviceId &&
    requestRoom.sessionToken === activeRoom.sessionToken
}

export function shouldContinueConnectionRequest(requestId: number, currentRequestId: number, cancelled: boolean): boolean {
  return !cancelled && requestId === currentRequestId
}

export function shouldShowDirectFailureDialog(phase: Phase, failure: string): boolean {
  if (phase === 'connected') return false
  return failure.trim() !== ''
}

export function shouldShowDirectFailureAfterRemoteRoomClosed(mode: ConnectionMode, phase: Phase, dataChannelOpen: boolean): boolean {
  if (dataChannelOpen || phase === 'connected') return false
  return phase === 'connecting' || (mode === 'peer' && phase === 'waiting')
}

export function directFailureGuidanceSteps(t: (key: Parameters<typeof translate>[1]) => string): string[] {
  return [
    t('directConnectionFailureSameNetwork'),
    t('directConnectionFailureVpnProxy'),
    t('directConnectionFailureGuestIsolation'),
    t('directConnectionFailureHotspot'),
  ]
}

export function shouldMonitorDirectConnectionAttempt(phase: Phase, dataChannelOpen: boolean): boolean {
  return phase === 'connecting' && !dataChannelOpen
}

export function shouldFailDirectConnectionAttempt(phase: Phase, dataChannelOpen: boolean, startedAtMs: number, nowMs: number, timeoutMs = directConnectionFailureTimeoutMs): boolean {
  if (!shouldMonitorDirectConnectionAttempt(phase, dataChannelOpen)) return false
  return nowMs - startedAtMs >= timeoutMs
}

export function shouldPreserveConnectedSessionDuringReconnect(phase: Phase, dataChannelOpen: boolean, source: ReconnectSource): boolean {
  void dataChannelOpen
  return phase === 'connected' && (source === 'signaling' || source === 'heartbeat' || source === 'transport')
}

export function shouldResetPeerRuntimeForReconnect(phase: Phase, dataChannelOpen: boolean, source: ReconnectSource): boolean {
  return !shouldPreserveConnectedSessionDuringReconnect(phase, dataChannelOpen, source)
}

export function shouldShowReconnectNotice(phase: Phase, dataChannelOpen: boolean, source: ReconnectSource): boolean {
  return !shouldPreserveConnectedSessionDuringReconnect(phase, dataChannelOpen, source)
}

export function shouldClearReconnectWindowAfterSignalingReconnect(source: ReconnectSource | null): boolean {
  return source !== 'transport'
}

export function phaseAfterSignalingReconnect(phase: Phase, mode: ConnectionMode, hasPeerDevice: boolean): Phase {
  if (phase === 'connecting' && mode === 'local' && !hasPeerDevice) return 'waiting'
  return phase
}

// The signaling server announces every re-authenticated socket as peer_joined,
// indistinguishable from a first join. Only a live transfer link to the same
// device tells them apart, and only while that link is up may the pairing
// dance be skipped; a dead or absent link must still re-offer to recover.
export function shouldIgnorePeerJoinedReauth(fromDeviceId: string, connectedPeerId: string, linkHealthy: boolean): boolean {
  return fromDeviceId !== '' && fromDeviceId === connectedPeerId && linkHealthy
}

// An announcement naming our own deviceId means a socket re-authed into our
// device slot — the shape a second tab of this browser produces when it takes
// over the session. Pairing against ourselves would orphan the real peer, so
// it is never acted on.
export function shouldIgnoreSelfPeerJoined(fromDeviceId: string, ownDeviceId: string): boolean {
  return fromDeviceId !== '' && fromDeviceId === ownDeviceId
}

// The session token lives in sessionStorage: a page reload keeps it (the same
// client came back and may re-prove its session), a new tab does not have one
// (and must not silently take over the slot the server side now refuses).
function rejoinSessionTokenKey(code: string): string {
  return `lanshare.rejoin.${code}`
}

export function readRejoinSessionToken(code: string): string | undefined {
  try {
    return sessionStorage.getItem(rejoinSessionTokenKey(code)) || undefined
  } catch {
    return undefined
  }
}

export function rememberRejoinSessionToken(code: string, sessionToken: string): void {
  try {
    sessionStorage.setItem(rejoinSessionTokenKey(code), sessionToken)
  } catch {
    // Storage can be unavailable (privacy mode). The join still works; only a
    // later reload of this page cannot re-prove the session.
  }
}

function getDirection(mode: ConnectionMode, phase: Phase, t: (key: Parameters<typeof translate>[1]) => string): string {
  if (mode === 'peer' && (phase === 'checking-network' || phase === 'checking-code')) return t('checkingPeerCode')
  if (mode === 'peer' && phase === 'connecting') return t('connectingPeer')
  if (mode === 'peer' && phase === 'connected') return t('connectedPeer')
  if (mode === 'local' && phase === 'connected') return t('peerConnectedThis')
  return t('waitingDirection')
}

function getSteps(phase: Phase): Record<string, StepState> {
  return {
    network: phase === 'checking-network' ? 'checking' : phase === 'failed' ? 'failed' : 'passed',
    code: phase === 'checking-code' ? 'checking' : ['connecting', 'connected'].includes(phase) ? 'passed' : 'wait',
    pairing: phase === 'connecting' ? 'pairing' : phase === 'connected' ? 'done' : 'wait',
    result: phase === 'connected' ? 'success' : phase === 'failed' ? 'failed' : 'wait',
  }
}

function isPairingCountdownPhase(phase: Phase): boolean {
  return phase === 'checking-network' || phase === 'checking-code' || phase === 'waiting'
}

export function isPeerConnectActivePhase(phase: Phase): boolean {
  return phase === 'checking-network' || phase === 'checking-code' || phase === 'waiting' || phase === 'connecting'
}

export function isPeerConnectBusy(mode: ConnectionMode, phase: Phase, loading: boolean): boolean {
  return loading || (mode === 'peer' && isPeerConnectActivePhase(phase))
}

export function shouldLockConnectionActions(mode: ConnectionMode, phase: Phase, loading: boolean): boolean {
  if (loading) return true
  if (phase === 'connecting') return true
  return mode === 'peer' && isPeerConnectActivePhase(phase)
}

export function shouldPausePairingCountdown(mode: ConnectionMode, phase: Phase, loading: boolean): boolean {
  return shouldLockConnectionActions(mode, phase, loading)
}

export function resumePairingDeadlineAfterPause(expiresAtMs: number, pausedAtMs: number, nowMs: number): number {
  return expiresAtMs + Math.max(0, nowMs - pausedAtMs)
}

export function disconnectedConnectionFormState(): { mode: ConnectionMode; peerCode: string; peerConnectLoading: boolean } {
  return {
    mode: 'local',
    peerCode: '',
    peerConnectLoading: false,
  }
}

export function localRoomRefreshReasonAfterConnectionExit(reason: ConnectionExitReason): LocalRoomRefreshReason | null {
  if (reason === 'active_disconnect') return 'manual'
  if (reason === 'remote_room_closed' || reason === 'session_expired' || reason === 'direct_connection_failed') return 'initial'
  return null
}

export function secondsUntil(expiresAtMs: number, nowMs = Date.now()): number {
  if (!Number.isFinite(expiresAtMs)) return ttlSeconds
  return Math.max(0, Math.ceil((expiresAtMs - nowMs) / 1000))
}

export function localRoomRetryDelay(attempt: number): number {
  return Math.min(localRoomRetryBaseDelayMs * 2 ** Math.max(0, attempt), localRoomRetryMaxDelayMs)
}

// Codes the server answers with a decision, not a hiccup: asking again with the
// same request only repeats the answer, so automatic retries must stop.
const terminalLocalRoomErrorCodes = new Set([
  'code_exhausted',
  'room_full',
  'expired_code',
  'invalid_session',
  'invalid_request',
  'invalid_device_type',
  'origin_not_allowed',
  'not_found',
])

// Heartbeat failures are ambiguous: a dropped packet and a dead server look the
// same from the browser. A run of them is not, so the verdict comes from how
// many of the recent beats failed rather than from a single miss.
export const heartbeatWindowSize = 6
export const minorHeartbeatFailureCount = 2
export const severeHeartbeatFailureCount = 4

export type HeartbeatHealth = 'healthy' | 'unstable' | 'broken'

// null means "do not heartbeat at all".
// A link that was never open is still pairing, so losing it says nothing about
// the network; only an established link can be lost to a switch.
// A dead transfer link means either "I changed networks" or "the other person
// left". Only the signaling server can tell them apart, so the verdict follows
// what it reports once the socket is back.
export function peerPresenceAfterReconnect(
  status: { paired: boolean; peers: Array<{ deviceId: string }> },
  ownDeviceId: string,
): 'present' | 'gone' {
  if (!status.paired) return 'gone'
  return status.peers.some((peer) => peer.deviceId !== ownDeviceId) ? 'present' : 'gone'
}

export function shouldReactToPeerLinkLoss(hasRuntime: boolean, dataChannelOpen: boolean): boolean {
  return hasRuntime && dataChannelOpen
}

export function heartbeatIntervalForPhase(phase: Phase): number | null {
  if (phase === 'connected') return heartbeatIntervalMs
  if (phase === 'connecting' || phase === 'waiting') return waitingHeartbeatIntervalMs
  return null
}

export function pushHeartbeatResult(window: boolean[], ok: boolean): boolean[] {
  const next = [...window, !ok]
  return next.length > heartbeatWindowSize ? next.slice(next.length - heartbeatWindowSize) : next
}

export function heartbeatHealth(window: boolean[]): HeartbeatHealth {
  // `window` holds one boolean per beat: true means that beat failed.
  const failures = window.filter(Boolean).length
  if (failures >= severeHeartbeatFailureCount) return 'broken'
  if (failures >= minorHeartbeatFailureCount) return 'unstable'
  return 'healthy'
}

// A server that names the room as gone is not guessing; no amount of retrying
// will bring it back, so this failure skips the window entirely.
export function isTerminalHeartbeatError(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'invalid_session'
}

export function isAmbiguousHeartbeatError(error: unknown): boolean {
  return !isTerminalHeartbeatError(error)
}

export function isTerminalLocalRoomFailure(error: unknown): boolean {
  return error instanceof ApiError && terminalLocalRoomErrorCodes.has(error.code)
}

export function localRoomRetryWindowExpired(deadlineMs: number, nowMs: number): boolean {
  return deadlineMs !== 0 && nowMs >= deadlineMs
}

export function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim() !== '') return error.message
  return fallback
}

export async function copyText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const input = document.createElement('textarea')
  input.value = value
  input.setAttribute('readonly', 'true')
  input.style.position = 'fixed'
  input.style.left = '-9999px'
  input.style.top = '0'
  document.body.appendChild(input)
  input.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(input)
  if (!copied) throw new Error('copy failed')
}

export function phaseLabel(phase: Phase, t: (key: Parameters<typeof translate>[1]) => string): string {
  const labels: Record<Phase, string> = {
    'checking-network': t('phaseCheckingNetwork'),
    'checking-code': t('phaseCheckingCode'),
    waiting: t('wait'),
    connecting: t('phaseConnecting'),
    connected: t('connected'),
    failed: t('phaseFailed'),
  }
  return labels[phase]
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatRemaining(seconds: number | null, t: (key: Parameters<typeof translate>[1]) => string): string {
  if (seconds === null || !Number.isFinite(seconds)) return `${t('eta')} --`
  if (seconds < 1) return `${t('eta')} <1s`
  const rounded = Math.ceil(seconds)
  const minutes = Math.floor(rounded / 60)
  const rest = rounded % 60
  if (minutes === 0) return `${t('eta')} ${rest}s`
  return `${t('eta')} ${minutes}m ${String(rest).padStart(2, '0')}s`
}

export function statusLabel(status: TransferStatus, t: (key: Parameters<typeof translate>[1]) => string): string {
  const labels: Record<TransferStatus, string> = {
    idle: t('statusIdle'),
    offered: t('statusReady'),
    waiting_accept: t('statusWaitingDownload'),
    transferring: t('statusTransferring'),
    complete: t('statusComplete'),
    failed: t('failed'),
    rejected: t('statusRejected'),
    canceled: t('statusCanceled'),
  }
  return labels[status]
}

export function fileStatusLabel(status: TransferStatus, t: (key: Parameters<typeof translate>[1]) => string): string {
  return statusLabel(status, t)
}
