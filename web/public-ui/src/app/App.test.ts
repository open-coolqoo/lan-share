import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement, createRef } from 'react'
import { androidDocumentFileAccept, heartbeatHealth, heartbeatIntervalForPhase, networkSwitchGraceMs, peerPresenceAfterReconnect, shouldReactToPeerLinkLoss, heartbeatIntervalMs, heartbeatWindowSize, waitingHeartbeatIntervalMs, isAmbiguousHeartbeatError, isTerminalHeartbeatError, minorHeartbeatFailureCount, pushHeartbeatResult, severeHeartbeatFailureCount, androidGalleryFileAccept, buildChatTimeline, buildPairingURL, canAutoJoinIncomingPairingCode, canCancelTransferFile, canDownloadPeerFile, collectDroppedFiles, confirmPendingFileDelete, deviceBadge, DirectFailureDialog, directConnectionFailureTimeoutMs, directFailureGuidanceSteps, disconnectedConnectionFormState, downloadButtonLabel, DroppedEntry, droppedEntries, EmojiPicker, emojiOptions, errorMessage, explicitLocalePath, FileDeleteConfirmationDialog, FileList, fileListPresentationReducer, fileStatusLabel, fileTypeBadge, footerLinkHref, googlePlayDownload, hasActiveFileTransfer, hasDroppedDirectory, initialFileListPresentationState, isContactFooterLink, isCurrentRoomRequest, isPeerConnectActivePhase, isPeerConnectBusy, isTerminalLocalRoomFailure, limitSelectedFiles, localRoomRefreshReasonAfterConnectionExit, localRoomRetryDelay, localRoomRetryWindowExpired, localizedFooterLinkLabel, MAX_FOLDER_FILES, MessageBubble, MessageThread, PeerMessageItem, pairingCodeFromURL, phaseAfterSignalingReconnect, phaseLabel, preferredActiveCode, requestFileDownload, resolveBrowserLocale, resolveInitialLocale, restoreFileListFocus, resumePairingDeadlineAfterPause, safePairingURLPath, secondsUntil, shouldAutoCreateLocalRoom, shouldContinueConnectionRequest, shouldCreateLocalRoomAfterPeerFailure, shouldClearReconnectWindowAfterSignalingReconnect, shouldDisplayPairingQr, shouldFailDirectConnectionAttempt, shouldIgnorePeerJoinedReauth, shouldIgnoreSelfPeerJoined, shouldMonitorDirectConnectionAttempt, shouldLockConnectionActions, shouldLockTransferStartActions, shouldPausePairingCountdown, shouldPreserveConnectedSessionDuringReconnect, shouldResetPeerRuntimeForReconnect, shouldShowDirectFailureAfterRemoteRoomClosed, shouldShowDirectFailureDialog, shouldShowFileProgress, shouldShowReconnectNotice, shouldSplitAndroidFilePickers, shouldTriggerBrowserDownloadAfterReceive, statusLabel, syncFileDownloadGuards, trapDeleteDialogTab, validatePeerCode } from './App'
import { Locale, supportedLocales, translate } from '../i18n/messages'
import { ApiError } from '../signaling/types'
import { TransferFileItem } from '../transfer/session'

describe('compact connected file list', () => {
  const t = (key: Parameters<typeof translate>[1]) => translate('en', key)
  const peerFiles: TransferFileItem[] = [
    {
      offer: {
        transferId: 'transfer-1',
        fileId: 'peer-1',
        name: 'a-very-long-file-name-that-must-not-displace-the-action-icons-at-mobile-width.zip',
        mime: 'application/zip',
        size: 8_192,
        chunkSize: 1_024,
        totalChunks: 8,
      },
      status: 'offered',
      progress: {
        bytesDone: 2_048,
        bytesTotal: 8_192,
        bytesPerSecond: 1_024,
        secondsRemaining: 6,
        percent: 25,
      },
      failure: 'Retry after reconnect',
      atMs: 1_000,
    },
  ]

  it('renders a file message bubble with type badge, ellipsizable name, size, status, and labeled icon actions before download', () => {
    const html = renderToStaticMarkup(createElement(FileList, {
      title: 'Peer files',
      emptyText: 'Waiting for peer file',
      files: peerFiles,
      receivedById: new Map(),
      t,
      actionsLocked: false,
      onDownload: vi.fn(),
      onCancel: vi.fn(),
    }))

    expect(html).toContain('file-type-badge is-archive')
    expect(html).toContain('>ZIP<')
    expect(html).toContain('class="file-name"')
    expect(html).toContain('<span dir="ltr">8.0 KB</span> · Ready')
    expect(html).toContain('aria-label="Download peer file"')
    expect(html).toContain('aria-label="Cancel transfer"')
    expect(html).toContain('title="Retry after reconnect"')
    expect(html).not.toContain('class="file-order"')
    expect(html).not.toContain('chunks')
    expect(html).not.toContain('file-progress')
    expect(html).not.toContain('role="progressbar"')
  })

  it('shows the in-bubble progress bar and transferring status while a download runs', () => {
    const transferring: TransferFileItem[] = [{ ...peerFiles[0], status: 'transferring' }]
    const html = renderToStaticMarkup(createElement(FileList, {
      title: 'Peer files',
      emptyText: 'Waiting for peer file',
      files: transferring,
      receivedById: new Map(),
      t,
      actionsLocked: false,
      onDownload: vi.fn(),
      onCancel: vi.fn(),
    }))

    expect(html).toContain('role="progressbar"')
    expect(html).toContain('aria-valuenow="25"')
    expect(html).toContain('width:25%')
    expect(html).toContain('<span dir="ltr">8.0 KB</span> · Transferring')
  })

  it('derives short type badges from extension and mime without an icon library', () => {
    expect(fileTypeBadge('report.final.pdf', 'application/pdf')).toEqual({ label: 'PDF', kind: 'pdf' })
    expect(fileTypeBadge('photo.jpg', 'image/jpeg')).toEqual({ label: 'JPG', kind: 'image' })
    expect(fileTypeBadge('clip', 'video/mp4')).toEqual({ label: 'MP4', kind: 'video' })
    expect(fileTypeBadge('archive.tar.gz', 'application/gzip')).toEqual({ label: 'GZ', kind: 'archive' })
    expect(fileTypeBadge('payload.bin', 'application/octet-stream')).toEqual({ label: 'BIN', kind: 'file' })
    expect(fileTypeBadge('blob', 'application/octet-stream')).toEqual({ label: 'FILE', kind: 'file' })
  })

  it('renders readable empty content across the full row without order or action controls', () => {
    const html = renderToStaticMarkup(createElement(FileList, {
      title: 'Peer files',
      emptyText: 'Waiting for peer file',
      files: [],
      t,
      actionsLocked: false,
      onDownload: vi.fn(),
      onCancel: vi.fn(),
    }))

    expect(html).toContain('class="empty-file-content"')
    expect(html).toContain('<strong>Waiting for peer file</strong>')
    expect(html).toContain('<span>No file content has been read.</span>')
    expect(html).not.toContain('class="file-order"')
    expect(html).not.toContain('file-row-action')
  })

  it('updates download presentation before invoking the existing callback', () => {
    const events: string[] = []
    const dispatch = vi.fn(() => events.push('presentation'))
    const onDownload = vi.fn(() => {
      events.push('callback')
      return true
    })
    const guardedIds = new Set<string>()

    requestFileDownload('peer-1', false, guardedIds, dispatch, onDownload)

    expect(dispatch).toHaveBeenCalledWith({ type: 'request-download', fileId: 'peer-1' })
    expect(onDownload).toHaveBeenCalledWith('peer-1')
    expect(events).toEqual(['presentation', 'callback'])
  })

  it('guards duplicate single-file download side effects immediately', () => {
    const guardedIds = new Set<string>()
    const dispatch = vi.fn()
    const onDownload = vi.fn(() => true)

    requestFileDownload('peer-1', false, guardedIds, dispatch, onDownload)
    requestFileDownload('peer-1', false, guardedIds, dispatch, onDownload)

    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(onDownload).toHaveBeenCalledTimes(1)
  })

  it('repeats received browser downloads without presentation or guard state', () => {
    const guardedIds = new Set<string>()
    const dispatch = vi.fn()
    const onDownload = vi.fn(() => true)

    requestFileDownload('peer-1', true, guardedIds, dispatch, onDownload)
    requestFileDownload('peer-1', true, guardedIds, dispatch, onDownload)

    expect(onDownload).toHaveBeenCalledTimes(2)
    expect(dispatch).not.toHaveBeenCalled()
    expect(guardedIds).toEqual(new Set())
  })

  it('rolls back single-file presentation and guard when synchronous start fails', () => {
    const guardedIds = new Set<string>()
    const dispatch = vi.fn()
    const onDownload = vi.fn(() => false)

    expect(requestFileDownload('peer-1', false, guardedIds, dispatch, onDownload)).toBe(false)

    expect(dispatch.mock.calls.map(([action]) => action)).toEqual([
      { type: 'request-download', fileId: 'peer-1' },
      { type: 'clear-download', fileIds: ['peer-1'] },
    ])
    expect(guardedIds).toEqual(new Set())
  })

  it('shows progress only for requested or transferring non-failure states', () => {
    expect(shouldShowFileProgress('offered', false)).toBe(false)
    expect(shouldShowFileProgress('offered', true)).toBe(true)
    expect(shouldShowFileProgress('waiting_accept', true)).toBe(true)
    expect(shouldShowFileProgress('transferring', false)).toBe(true)
    expect(shouldShowFileProgress('complete', true)).toBe(false)
    expect(shouldShowFileProgress('failed', true)).toBe(false)
    expect(shouldShowFileProgress('rejected', true)).toBe(false)
    expect(shouldShowFileProgress('canceled', true)).toBe(false)
  })

  it('clears requested presentation and duplicate guards for missing, complete, or failed files', () => {
    const requested = fileListPresentationReducer(initialFileListPresentationState, { type: 'request-download', fileId: 'peer-1' })
    const failed = fileListPresentationReducer(requested, { type: 'sync-files', files: [{ fileId: 'peer-1', status: 'failed' }] })
    expect(failed.requestedDownloadIds).toEqual(new Set())
    const complete = fileListPresentationReducer(requested, { type: 'sync-files', files: [{ fileId: 'peer-1', status: 'complete' }] })
    expect(complete.requestedDownloadIds).toEqual(new Set())

    const guardedIds = new Set(['peer-1', 'peer-2', 'peer-3'])
    syncFileDownloadGuards(guardedIds, [
      { fileId: 'peer-1', status: 'complete' },
      { fileId: 'peer-2', status: 'offered' },
    ])
    expect(guardedIds).toEqual(new Set(['peer-2']))
  })

  it('consumes a pending file and invokes cancellation exactly once with its file id', () => {
    const pending = { current: peerFiles[0] as TransferFileItem | null }
    const dispatch = vi.fn()
    const onCancel = vi.fn()

    confirmPendingFileDelete(pending, dispatch, onCancel)
    confirmPendingFileDelete(pending, dispatch, onCancel)

    expect(pending.current).toBeNull()
    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(dispatch).toHaveBeenCalledWith({ type: 'close-delete' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onCancel).toHaveBeenCalledWith('peer-1')
  })

  it('renders an accessible localized confirmation dialog containing the filename', () => {
    const html = renderToStaticMarkup(createElement(FileDeleteConfirmationDialog, {
      item: peerFiles[0],
      t,
      titleId: 'remove-file-title',
      cancelButtonRef: createRef<HTMLButtonElement>(),
      confirmButtonRef: createRef<HTMLButtonElement>(),
      onClose: vi.fn(),
      onConfirm: vi.fn(),
    }))

    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-modal="true"')
    expect(html).toContain('aria-labelledby="remove-file-title"')
    expect(html).toContain('id="remove-file-title"')
    expect(html).toContain(peerFiles[0].offer.name)
    expect(html).toContain('>Keep file<')
    expect(html).toContain('>Remove<')
  })

  it('restores focus to the delete trigger on close and to the heading after confirm', () => {
    const trigger = { focus: vi.fn() }
    const heading = { focus: vi.fn() }

    restoreFileListFocus(false, trigger, heading)
    restoreFileListFocus(true, trigger, heading)

    expect(trigger.focus).toHaveBeenCalledTimes(1)
    expect(heading.focus).toHaveBeenCalledTimes(1)
  })

  it('wraps Tab within the confirmation actions', () => {
    const cancel = { focus: vi.fn() }
    const confirm = { focus: vi.fn() }
    const preventDefault = vi.fn()

    trapDeleteDialogTab({ key: 'Tab', shiftKey: true, target: cancel, preventDefault }, cancel, confirm)
    expect(confirm.focus).toHaveBeenCalledOnce()
    trapDeleteDialogTab({ key: 'Tab', shiftKey: false, target: confirm, preventDefault }, cancel, confirm)
    expect(cancel.focus).toHaveBeenCalledOnce()
    expect(preventDefault).toHaveBeenCalledTimes(2)
  })

  it('marks only the clicked file requested before download and removes stale requested ids', () => {
    const requested = fileListPresentationReducer(initialFileListPresentationState, { type: 'request-download', fileId: 'peer-1' })
    expect(requested.requestedDownloadIds).toEqual(new Set(['peer-1']))

    const cleaned = fileListPresentationReducer(requested, { type: 'sync-files', files: [{ fileId: 'peer-2', status: 'offered' }] })
    expect(cleaned.requestedDownloadIds).toEqual(new Set())
  })

  it('requires confirmation before cancellation and clears stale pending deletion', () => {
    const pending = fileListPresentationReducer(initialFileListPresentationState, { type: 'request-delete', file: peerFiles[0] })
    expect(pending.pendingDelete?.offer.name).toBe(peerFiles[0].offer.name)

    const canceled = fileListPresentationReducer(pending, { type: 'close-delete' })
    expect(canceled.pendingDelete).toBeNull()

    const stale = fileListPresentationReducer(pending, { type: 'sync-files', files: [] })
    expect(stale.pendingDelete).toBeNull()
  })

  it('provides complete localized delete-confirmation copy for every locale', () => {
    const keys = ['removeFileTitle', 'removeFileMessage', 'removeFileCancel', 'removeFileConfirm'] as const
    for (const locale of supportedLocales) {
      for (const key of keys) {
        expect(translate(locale, key)).not.toBe(key)
        if (locale !== 'en') expect(translate(locale, key)).not.toBe(translate('en', key))
      }
      expect(translate(locale, 'removeFileMessage').match(/\{filename\}/g)).toHaveLength(1)
    }
  })
})

describe('homepage download link', () => {
  it('points to the official Google Play listing with safe external-link attributes', () => {
    expect(googlePlayDownload).toEqual({
      label: 'Google Play',
      url: 'https://play.google.com/store/apps/details?id=com.lanshare',
      target: '_blank',
      rel: 'noopener noreferrer',
    })
  })
})

describe('footer links', () => {
  it('filters the default mailto Contact link from footer navigation', () => {
    expect(isContactFooterLink({ label: 'Contact', url: 'mailto:support@lanshare.app' })).toBe(true)
    expect(isContactFooterLink({ label: 'Contact', url: '/contact' })).toBe(false)
    expect(isContactFooterLink({ label: 'Support', url: 'mailto:support@lanshare.app' })).toBe(false)
  })
})

describe('local room auto-start helper', () => {
  it('starts the pairing room for normal homepage visitors', () => {
    expect(shouldAutoCreateLocalRoom('Mozilla/5.0 Chrome/143.0 Safari/537.36', false)).toBe(true)
  })

  it('does not create rooms or open signaling during Lighthouse audits', () => {
    expect(shouldAutoCreateLocalRoom('Mozilla/5.0 Chrome/143.0 Mobile Safari/537.36 Chrome-Lighthouse', false)).toBe(false)
    expect(shouldAutoCreateLocalRoom('Mozilla/5.0 Chrome/143.0 Safari/537.36', true)).toBe(false)
  })

  it('does not create a local room while a scanned pairing link is joining', () => {
    expect(shouldAutoCreateLocalRoom('Mozilla/5.0 Chrome/143.0 Safari/537.36', false, true)).toBe(false)
  })

  it('creates a fresh local room after an expired scanned pairing link fails', () => {
    expect(shouldCreateLocalRoomAfterPeerFailure(new ApiError('This connection code has expired.', 'expired_code', 409), true)).toBe(true)
    expect(shouldCreateLocalRoomAfterPeerFailure(new ApiError('This connection code is already paired.', 'room_full', 409), true)).toBe(false)
    expect(shouldCreateLocalRoomAfterPeerFailure(new ApiError('This connection code has expired.', 'expired_code', 409), false)).toBe(false)
    expect(shouldCreateLocalRoomAfterPeerFailure(new Error('Network error'), true)).toBe(false)
  })

  it('auto-joins once for a scanned pairing link', () => {
    expect(canAutoJoinIncomingPairingCode('49382716', false, 'local', 'checking-network')).toBe(true)
    expect(canAutoJoinIncomingPairingCode(null, false, 'local', 'checking-network')).toBe(false)
    expect(canAutoJoinIncomingPairingCode('49382716', true, 'local', 'checking-network')).toBe(false)
    expect(canAutoJoinIncomingPairingCode('49382716', false, 'peer', 'connecting')).toBe(false)
  })
})

describe('heartbeat request freshness helper', () => {
  const currentRoom = {
    roomId: 'room_current',
    deviceId: 'device_a',
    sessionToken: 'sess_current',
  }

  it('accepts only the current room request version', () => {
    expect(isCurrentRoomRequest(currentRoom, currentRoom)).toBe(true)
    expect(isCurrentRoomRequest({ ...currentRoom }, currentRoom)).toBe(true)
    expect(isCurrentRoomRequest(currentRoom, null)).toBe(false)
    expect(isCurrentRoomRequest({ ...currentRoom, roomId: 'room_old' }, currentRoom)).toBe(false)
    expect(isCurrentRoomRequest({ ...currentRoom, sessionToken: 'sess_old' }, currentRoom)).toBe(false)
    expect(isCurrentRoomRequest({ ...currentRoom, deviceId: 'device_b' }, currentRoom)).toBe(false)
  })
})

describe('connection request freshness helper', () => {
  it('stops a stale local room refresh before it can close a newer peer signaling session', () => {
    expect(shouldContinueConnectionRequest(7, 7, false)).toBe(true)
    expect(shouldContinueConnectionRequest(7, 8, false)).toBe(false)
    expect(shouldContinueConnectionRequest(7, 7, true)).toBe(false)
  })
})

describe('localized footer links', () => {
  const t = (locale: Locale) => (key: Parameters<typeof translate>[1]) => translate(locale, key)

  it('localizes the six public page labels from their URLs', () => {
    const links = [
      { label: 'Privacy', url: '/privacy' },
      { label: 'Terms', url: '/terms' },
      { label: 'Security', url: '/security' },
      { label: 'FAQ', url: '/faq' },
      { label: 'Compatibility', url: '/compatibility' },
      { label: 'About', url: '/about' },
    ]

    expect(links.map((link) => localizedFooterLinkLabel(link, t('zh-CN')))).toEqual([
      '隐私政策',
      '使用条款',
      '安全说明',
      '常见问题',
      '兼容性',
      '关于',
    ])
  })

  it('prefixes footer link hrefs with the active locale', () => {
    expect(footerLinkHref({ label: 'Privacy', url: '/privacy' }, 'en')).toBe('/privacy')
    expect(footerLinkHref({ label: 'Privacy', url: '/privacy' }, 'zh-CN')).toBe('/zh-CN/privacy')
    expect(footerLinkHref({ label: 'FAQ', url: '/faq#pairing' }, 'ja')).toBe('/ja/faq#pairing')
    expect(footerLinkHref({ label: 'FAQ', url: '/faq?ref=footer' }, 'zh-TW')).toBe('/zh-TW/faq?ref=footer')
    expect(footerLinkHref({ label: 'Privacy', url: '/zh-CN/privacy' }, 'zh-CN')).toBe('/zh-CN/privacy')
    expect(footerLinkHref({ label: 'Contact', url: 'mailto:support@lanshare.example' }, 'zh-CN')).toBe('mailto:support@lanshare.example')
  })
})

describe('homepage connection guidance copy', () => {
  it('describes the same-LAN success and speed advantage', () => {
    expect(translate('en', 'selfCodeHint')).toContain('Same-LAN connections usually have the best success rate and speed.')
    expect(translate('zh-CN', 'selfCodeHint')).toContain('同局域网连接通常成功率最高、速度最快。')
  })
})

describe('direct-only failure guidance helper', () => {
  const t = (locale: Locale) => (key: Parameters<typeof translate>[1]) => translate(locale, key)

  it('shows network and hotspot guidance instead of server forwarding authorization', () => {
    expect(shouldShowDirectFailureDialog('failed', 'Direct connection failed.')).toBe(true)
    expect(shouldShowDirectFailureDialog('waiting', 'Direct connection failed.')).toBe(true)
    expect(shouldShowDirectFailureDialog('waiting', '直连失败。')).toBe(true)
    expect(shouldShowDirectFailureDialog('connected', 'Direct connection failed.')).toBe(false)
    expect(directFailureGuidanceSteps(t('zh-CN')).join(' ')).toContain('手机热点')
    expect(directFailureGuidanceSteps(t('zh-CN')).join(' ')).toContain('同一个 Wi-Fi')
    expect(translate('zh-CN', 'directConnectionFailureHint')).not.toContain('中继')
  })

  it('renders direct failure guidance with only a close icon action', () => {
    const html = renderToStaticMarkup(DirectFailureDialog({
      t: t('en'),
      failure: 'Direct connection failed.',
      onClose: () => undefined,
    }))

    expect(html).toContain('direct-failure-close')
    expect(html).toContain(`aria-label="${translate('en', 'closeDialog')}"`)
    expect(html).not.toContain('class="primary"')
  })

  it('fails a direct connection attempt that stays connecting past the timeout', () => {
    expect(shouldMonitorDirectConnectionAttempt('connecting', false)).toBe(true)
    expect(shouldMonitorDirectConnectionAttempt('connecting', true)).toBe(false)
    expect(shouldMonitorDirectConnectionAttempt('connected', false)).toBe(false)
    expect(shouldFailDirectConnectionAttempt('connecting', false, 1_000, 1_000 + directConnectionFailureTimeoutMs)).toBe(true)
    expect(shouldFailDirectConnectionAttempt('connecting', false, 1_000, 1_000 + directConnectionFailureTimeoutMs - 1)).toBe(false)
    expect(shouldFailDirectConnectionAttempt('connecting', true, 1_000, 1_000 + directConnectionFailureTimeoutMs)).toBe(false)
    expect(shouldFailDirectConnectionAttempt('connected', false, 1_000, 1_000 + directConnectionFailureTimeoutMs)).toBe(false)
  })

  it('shows direct failure when the active connector receives remote room close before data channel opens', () => {
    expect(shouldShowDirectFailureAfterRemoteRoomClosed('peer', 'connecting', false)).toBe(true)
    expect(shouldShowDirectFailureAfterRemoteRoomClosed('peer', 'waiting', false)).toBe(true)
    expect(shouldShowDirectFailureAfterRemoteRoomClosed('local', 'connecting', false)).toBe(true)
    expect(shouldShowDirectFailureAfterRemoteRoomClosed('peer', 'connected', true)).toBe(false)
    expect(shouldShowDirectFailureAfterRemoteRoomClosed('local', 'waiting', false)).toBe(false)
  })
})

describe('heartbeat failure policy', () => {
  it('keeps a window of the most recent beats', () => {
    let w: boolean[] = []
    for (let i = 0; i < heartbeatWindowSize + 3; i++) w = pushHeartbeatResult(w, true)
    expect(w.length).toBe(heartbeatWindowSize)
  })

  it('stays healthy through one isolated miss', () => {
    let w = pushHeartbeatResult([], false)
    expect(heartbeatHealth(w)).toBe('healthy')
    w = pushHeartbeatResult(pushHeartbeatResult(w, true), true)
    expect(heartbeatHealth(w)).toBe('healthy')
  })

  it('calls the network unstable only after repeated misses', () => {
    let w: boolean[] = []
    for (let i = 0; i < minorHeartbeatFailureCount - 1; i++) w = pushHeartbeatResult(w, false)
    expect(heartbeatHealth(w)).toBe('healthy')
    w = pushHeartbeatResult(w, false)
    expect(heartbeatHealth(w)).toBe('unstable')
  })

  it('does not declare a broken connection while most beats still work', () => {
    // Alternating failure and success is bad but not fatal.
    let w: boolean[] = []
    for (let i = 0; i < heartbeatWindowSize; i++) w = pushHeartbeatResult(w, i % 2 === 0)
    expect(heartbeatHealth(w)).toBe('unstable')
  })

  it('declares a broken connection once most recent beats fail', () => {
    let w: boolean[] = []
    for (let i = 0; i < severeHeartbeatFailureCount; i++) w = pushHeartbeatResult(w, false)
    expect(heartbeatHealth(w)).toBe('broken')
  })

  it('recovers when the network comes back', () => {
    let w: boolean[] = []
    for (let i = 0; i < heartbeatWindowSize; i++) w = pushHeartbeatResult(w, false)
    expect(heartbeatHealth(w)).toBe('broken')
    for (let i = 0; i < heartbeatWindowSize; i++) w = pushHeartbeatResult(w, true)
    expect(heartbeatHealth(w)).toBe('healthy')
  })

  it('only keeps a heartbeat alive where there is something to keep alive', () => {
    // A live transfer is the only state that needs close attention.
    expect(heartbeatIntervalForPhase('connected')).toBe(heartbeatIntervalMs)
    // A waiting code only needs the room kept open, not a fast poll.
    expect(heartbeatIntervalForPhase('waiting')).toBe(waitingHeartbeatIntervalMs)
    expect(heartbeatIntervalForPhase('connecting')).toBe(waitingHeartbeatIntervalMs)
    // Nothing paired, nothing to maintain.
    expect(heartbeatIntervalForPhase('failed')).toBeNull()
    expect(heartbeatIntervalForPhase('checking-network')).toBeNull()
    expect(heartbeatIntervalForPhase('checking-code')).toBeNull()
  })

  it('treats a vanished session as terminal without waiting for a window', () => {
    const gone = new ApiError('gone', 'invalid_session', 410)
    expect(isTerminalHeartbeatError(gone)).toBe(true)
    expect(isAmbiguousHeartbeatError(gone)).toBe(false)
    // A timeout says nothing about whether the room still exists.
    expect(isTerminalHeartbeatError(new Error('Request timed out.'))).toBe(false)
    expect(isAmbiguousHeartbeatError(new Error('Request timed out.'))).toBe(true)
    expect(isTerminalHeartbeatError(new ApiError('boom', 'http_500', 500))).toBe(false)
    expect(isAmbiguousHeartbeatError(new ApiError('boom', 'http_500', 500))).toBe(true)
  })
})

describe('network switch policy', () => {
  it('gives a changed network a few seconds to heal before bothering anyone', () => {
    // Long enough for ICE to restart, short enough to feel responsive.
    expect(networkSwitchGraceMs).toBeGreaterThanOrEqual(3_000)
    expect(networkSwitchGraceMs).toBeLessThanOrEqual(10_000)
  })

  it('explains the cause rather than blaming the user', () => {
    for (const locale of ['en', 'zh-CN', 'zh-TW'] as Locale[]) {
      const text = translate(locale, 'networkSwitched')
      expect(text.trim().length).toBeGreaterThan(0)
      expect(text).not.toContain('{')
    }
  })

  it('blames the network only when the peer is still there', () => {
    const me = 'dev_me'
    // Signaling says the other device is gone: they left, not a network switch.
    expect(peerPresenceAfterReconnect({ paired: true, peers: [{ deviceId: me }] }, me)).toBe('gone')
    expect(peerPresenceAfterReconnect({ paired: false, peers: [{ deviceId: me }] }, me)).toBe('gone')
    // Both still in the room while the transfer link is dead: this device moved.
    expect(peerPresenceAfterReconnect(
      { paired: true, peers: [{ deviceId: me }, { deviceId: 'dev_them' }] },
      me,
    )).toBe('present')
  })

  it('ignores a link loss while pairing, since nothing was lost yet', () => {
    expect(shouldReactToPeerLinkLoss(true, true)).toBe(true)
    expect(shouldReactToPeerLinkLoss(true, false)).toBe(false)
    expect(shouldReactToPeerLinkLoss(false, true)).toBe(false)
    expect(shouldReactToPeerLinkLoss(false, false)).toBe(false)
  })
})

describe('localized status helpers', () => {
  const t = (locale: Locale) => (key: Parameters<typeof translate>[1]) => translate(locale, key)

  it('localizes connection phases', () => {
    expect(phaseLabel('checking-network', t('en'))).toBe('Checking network')
    expect(phaseLabel('failed', t('zh-CN'))).toBe('连接失败')
  })

  it('localizes transfer statuses', () => {
    expect(statusLabel('waiting_accept', t('en'))).toBe('Waiting for download')
    expect(statusLabel('transferring', t('zh-CN'))).toBe('传输中')
  })

})

describe('peer connect loading helper', () => {
  it('locks the connect action while a peer join attempt is active', () => {
    expect(isPeerConnectActivePhase('checking-network')).toBe(true)
    expect(isPeerConnectActivePhase('checking-code')).toBe(true)
    expect(isPeerConnectActivePhase('waiting')).toBe(true)
    expect(isPeerConnectActivePhase('connecting')).toBe(true)
  })

  it('releases the connect action after terminal or inactive phases', () => {
    expect(isPeerConnectActivePhase('failed')).toBe(false)
    expect(isPeerConnectActivePhase('connected')).toBe(false)
    expect(isPeerConnectActivePhase('failed')).toBe(false)
  })

  it('does not treat local room setup as a peer connect attempt', () => {
    expect(isPeerConnectBusy('local', 'checking-network', false)).toBe(false)
    expect(isPeerConnectBusy('local', 'waiting', false)).toBe(false)
    expect(isPeerConnectBusy('peer', 'waiting', false)).toBe(true)
    expect(isPeerConnectBusy('local', 'waiting', true)).toBe(true)
  })

  it('locks other connection actions for both sides only during active connection work', () => {
    expect(shouldLockConnectionActions('local', 'waiting', false)).toBe(false)
    expect(shouldLockConnectionActions('local', 'checking-network', false)).toBe(false)
    expect(shouldLockConnectionActions('local', 'connecting', false)).toBe(true)
    expect(shouldLockConnectionActions('peer', 'checking-code', false)).toBe(true)
    expect(shouldLockConnectionActions('peer', 'waiting', false)).toBe(true)
    expect(shouldLockConnectionActions('peer', 'connected', false)).toBe(false)
    expect(shouldLockConnectionActions('local', 'waiting', true)).toBe(true)
  })
})

describe('signaling reconnect phase helper', () => {
  it('restores local waiting after signaling reconnect so the pairing countdown keeps running', () => {
    expect(phaseAfterSignalingReconnect('connecting', 'local', false)).toBe('waiting')
  })

  it('keeps active peer connection attempts in connecting after signaling reconnect', () => {
    expect(phaseAfterSignalingReconnect('connecting', 'peer', true)).toBe('connecting')
    expect(phaseAfterSignalingReconnect('connected', 'local', false)).toBe('connected')
  })

  it('keeps a connected page and transfer state mounted during passive reconnects', () => {
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', true, 'signaling')).toBe(true)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', true, 'heartbeat')).toBe(true)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', true, 'peer-left')).toBe(false)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', true, 'transport')).toBe(true)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', false, 'signaling')).toBe(true)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', false, 'peer-left')).toBe(false)
    expect(shouldPreserveConnectedSessionDuringReconnect('connected', false, 'transport')).toBe(true)
  })

  it('tears down the peer runtime only for unpaired reconnects', () => {
    expect(shouldResetPeerRuntimeForReconnect('connected', true, 'signaling')).toBe(false)
    expect(shouldResetPeerRuntimeForReconnect('connected', true, 'heartbeat')).toBe(false)
    expect(shouldResetPeerRuntimeForReconnect('connected', true, 'peer-left')).toBe(true)
    expect(shouldResetPeerRuntimeForReconnect('connected', true, 'transport')).toBe(false)
    expect(shouldResetPeerRuntimeForReconnect('connecting', false, 'signaling')).toBe(true)
    expect(shouldResetPeerRuntimeForReconnect('connected', false, 'signaling')).toBe(false)
    expect(shouldResetPeerRuntimeForReconnect('connected', false, 'transport')).toBe(false)
  })

  it('hides passive reconnect notices while remote peer leaves reset the connected page', () => {
    expect(shouldShowReconnectNotice('connected', true, 'signaling')).toBe(false)
    expect(shouldShowReconnectNotice('connected', true, 'heartbeat')).toBe(false)
    expect(shouldShowReconnectNotice('connected', true, 'peer-left')).toBe(true)
    expect(shouldShowReconnectNotice('connected', true, 'transport')).toBe(false)
    expect(shouldShowReconnectNotice('connected', false, 'signaling')).toBe(false)
    expect(shouldShowReconnectNotice('connected', false, 'transport')).toBe(false)
    expect(shouldShowReconnectNotice('connecting', false, 'signaling')).toBe(true)
  })

  it('keeps the reconnect expiry window active after transport signaling reconnects', () => {
    expect(shouldClearReconnectWindowAfterSignalingReconnect('signaling')).toBe(true)
    expect(shouldClearReconnectWindowAfterSignalingReconnect('heartbeat')).toBe(true)
    expect(shouldClearReconnectWindowAfterSignalingReconnect('peer-left')).toBe(true)
    expect(shouldClearReconnectWindowAfterSignalingReconnect('transport')).toBe(false)
    expect(shouldClearReconnectWindowAfterSignalingReconnect(null)).toBe(true)
  })
})

describe('connection exit form reset helper', () => {
  it('returns the connect form to an idle empty state after active or passive disconnects', () => {
    expect(disconnectedConnectionFormState()).toEqual({
      mode: 'local',
      peerCode: '',
      peerConnectLoading: false,
    })
  })

  it('refreshes the local pairing room after terminal connection exits without interrupting transient reconnects', () => {
    expect(localRoomRefreshReasonAfterConnectionExit('active_disconnect')).toBe('manual')
    expect(localRoomRefreshReasonAfterConnectionExit('remote_room_closed')).toBe('initial')
    expect(localRoomRefreshReasonAfterConnectionExit('session_expired')).toBe('initial')
    expect(localRoomRefreshReasonAfterConnectionExit('direct_connection_failed')).toBe('initial')
    expect(localRoomRefreshReasonAfterConnectionExit('transient_disconnect')).toBeNull()
  })
})

describe('peer download availability helper', () => {
  const label = (locale: Locale) => (key: Parameters<typeof translate>[1]) => translate(locale, key)

  it('allows downloading offered peer files', () => {
    expect(canDownloadPeerFile({ status: 'offered' }, false)).toBe(true)
  })

  it('allows downloading completed peer files again after they have been received', () => {
    expect(canDownloadPeerFile({ status: 'complete' }, true)).toBe(true)
  })

  it('requires an offered or received peer file outside the active transfer', () => {
    expect(canDownloadPeerFile({ status: 'complete' }, false)).toBe(false)
    expect(canDownloadPeerFile({ status: 'transferring' }, true)).toBe(false)
  })

  it('keeps received files labeled after the user downloads them', () => {
    expect(downloadButtonLabel('complete', true, false, label('en'))).toBe('Downloaded')
    expect(downloadButtonLabel('complete', false, false, label('en'))).toBe('Download peer file')
    expect(downloadButtonLabel('transferring', false, false, label('en'))).toBe('Downloading')
  })

  it('auto-downloads only files the user explicitly requested', () => {
    expect(shouldTriggerBrowserDownloadAfterReceive('file_ready', new Set(['file_ready']))).toBe(true)
    expect(shouldTriggerBrowserDownloadAfterReceive('file_auto', new Set())).toBe(false)
  })
})

describe('transfer cancellation availability helper', () => {
  it('allows cancel actions before download and after completion', () => {
    expect(canCancelTransferFile({ status: 'waiting_accept' })).toBe(true)
    expect(canCancelTransferFile({ status: 'offered' })).toBe(true)
    expect(canCancelTransferFile({ status: 'complete' })).toBe(true)
  })

  it('allows cancel actions while the file is transferring', () => {
    expect(canCancelTransferFile({ status: 'transferring' })).toBe(true)
    expect(canCancelTransferFile({ status: 'failed' })).toBe(true)
    expect(canCancelTransferFile({ status: 'rejected' })).toBe(true)
  })
})

describe('active transfer action lock helper', () => {
  it('locks surrounding actions when either side is transferring', () => {
    expect(hasActiveFileTransfer([{ status: 'complete' }], [{ status: 'transferring' }])).toBe(true)
    expect(hasActiveFileTransfer([{ status: 'transferring' }], [{ status: 'offered' }])).toBe(true)
  })

  it('locks starting new transfers while a transfer is active', () => {
    expect(shouldLockTransferStartActions('transferring', [], [])).toBe(true)
    expect(shouldLockTransferStartActions('complete', [], [])).toBe(false)
  })

  it('does not lock actions after files are offered or completed', () => {
    expect(hasActiveFileTransfer([{ status: 'waiting_accept' }], [{ status: 'offered' }, { status: 'complete' }])).toBe(false)
  })
})

describe('native file input compatibility helper', () => {
  it('splits Android file and gallery pickers so document files remain selectable', () => {
    expect(shouldSplitAndroidFilePickers('Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 Chrome/143.0 Mobile Safari/537.36')).toBe(true)
  })

  it('keeps multi-file input on desktop browsers', () => {
    expect(shouldSplitAndroidFilePickers('Mozilla/5.0 (Macintosh; Intel Mac OS X 15_2) AppleWebKit/537.36 Chrome/143.0 Safari/537.36')).toBe(false)
  })

  it('keeps the Android document picker separate from media capture sources', () => {
    expect(androidDocumentFileAccept).toContain('application/*')
    expect(androidDocumentFileAccept).toContain('text/*')
    expect(androidDocumentFileAccept).not.toContain('image/*')
    expect(androidDocumentFileAccept).not.toContain('video/*')
    expect(androidGalleryFileAccept).toBe('image/*,video/*')
  })
})

describe('localized error fallback helper', () => {
  it('preserves concrete runtime errors', () => {
    expect(errorMessage(new Error('Room is full.'), 'Connection failed.')).toBe('Room is full.')
  })

  it('falls back to localized copy for empty or non-error failures', () => {
    expect(errorMessage(new Error(''), translate('en', 'errorConnectionFailed'))).toBe('Connection failed.')
    expect(errorMessage('failed', translate('zh-CN', 'errorConnectionFailed'))).toBe('连接失败。')
  })
})

describe('peer code validation', () => {
  it('rejects connecting to this browser local code', () => {
    expect(validatePeerCode('49382716', '49382716', [])).toBe('self')
  })

  it('rejects refreshed local codes before attempting join', () => {
    expect(validatePeerCode('49382716', '02849173', ['49382716'])).toBe('refreshed')
  })

  it('rejects malformed or weak codes before attempting join', () => {
    expect(validatePeerCode('12345678', '02849173', [])).toBe('invalid')
    expect(validatePeerCode('1234', '02849173', [])).toBe('invalid')
  })

  it('accepts a different usable peer code', () => {
    expect(validatePeerCode('49382716', '02849173', [])).toBe('ok')
  })
})

describe('summary card code during peer_joined', () => {
  it('keeps the code the current session already set', () => {
    // The room for this session has opened; a stale closure may still carry
    // the previous session's localCode and must not overwrite it.
    expect(preferredActiveCode('02849173', '49382716')).toBe('02849173')
  })

  it('fills a blank card from the captured code', () => {
    // peer_joined can beat the room-open update, leaving the card empty.
    expect(preferredActiveCode('', '49382716')).toBe('49382716')
  })

  it('stays blank when neither source has a code yet', () => {
    expect(preferredActiveCode('', '')).toBe('')
    expect(preferredActiveCode('02849173', '')).toBe('02849173')
  })
})

describe('peer_joined re-auth guard', () => {
  it('ignores peer_joined from the connected peer while the transfer link is up', () => {
    // A peer re-authenticating its signaling socket is announced exactly like
    // a first join; with the link alive it must not restart the pairing dance.
    expect(shouldIgnorePeerJoinedReauth('device_peer', 'device_peer', true)).toBe(true)
  })

  it('re-offers when the link to the same device is gone', () => {
    // The pairing dance is the recovery path for a lost transport, so a
    // half-dead link must still trigger it.
    expect(shouldIgnorePeerJoinedReauth('device_peer', 'device_peer', false)).toBe(false)
  })

  it('treats unknown or unpaired devices as a fresh pairing', () => {
    expect(shouldIgnorePeerJoinedReauth('device_peer', '', true)).toBe(false)
    expect(shouldIgnorePeerJoinedReauth('device_other', 'device_peer', true)).toBe(false)
    expect(shouldIgnorePeerJoinedReauth('', 'device_peer', true)).toBe(false)
  })
})

describe('self peer_joined guard', () => {
  it('ignores an announcement naming our own deviceId', () => {
    // A same-slot socket re-authing (the second-tab hijack shape) must never
    // start a pairing dance against ourselves or overwrite the known peer.
    expect(shouldIgnoreSelfPeerJoined('device_self', 'device_self')).toBe(true)
  })

  it('keeps every genuine peer announcement', () => {
    expect(shouldIgnoreSelfPeerJoined('device_peer', 'device_self')).toBe(false)
    expect(shouldIgnoreSelfPeerJoined('', 'device_self')).toBe(false)
    expect(shouldIgnoreSelfPeerJoined('device_peer', '')).toBe(false)
  })
})

describe('pairing URL helpers', () => {
  it('builds a same-origin pairing URL that carries only the short-lived code and a locale path', () => {
    const url = buildPairingURL('https://lanshare.example/privacy?utm=source#docs', '49382716', 'zh-CN')

    expect(url).toBe('https://lanshare.example/zh-CN?pair=49382716')
    expect(url).not.toContain('room_')
    expect(url).not.toContain('sess_')
  })

  it('keeps English explicit when the receiver has a different saved language', () => {
    const url = new URL(buildPairingURL('https://lanshare.example/', '49382716', 'en'))
    expect(url.toString()).toBe('https://lanshare.example/en?pair=49382716')
    expect(resolveInitialLocale('zh-CN', url.pathname, url.search, ['zh-CN'])).toEqual({ locale: 'en', explicit: true, source: 'url' })
  })

  it('extracts and normalizes a usable pairing code from the URL', () => {
    expect(pairingCodeFromURL('?pair=4938-2716&locale=en')).toBe('49382716')
  })

  it('rejects malformed, weak, or absent pairing codes from the URL', () => {
    expect(pairingCodeFromURL('?pair=12345678')).toBeNull()
    expect(pairingCodeFromURL('?pair=4938')).toBeNull()
    expect(pairingCodeFromURL('?locale=en')).toBeNull()
  })

  it('removes the pairing code from the visible URL while preserving the locale path and hash', () => {
    expect(safePairingURLPath('/zh-CN?pair=49382716#top')).toBe('/zh-CN#top')
    expect(safePairingURLPath('/?pair=49382716')).toBe('/')
  })

  it('only displays a generated QR code when it matches the visible local code', () => {
    expect(shouldDisplayPairingQr('49382716', { code: '49382716', dataUrl: 'data:image/png;base64,current' })).toBe(true)
    expect(shouldDisplayPairingQr('49382716', { code: '02849173', dataUrl: 'data:image/png;base64:old' })).toBe(false)
    expect(shouldDisplayPairingQr('49382716', { code: '49382716', dataUrl: '' })).toBe(false)
  })
})

describe('pairing countdown helper', () => {
  it('derives remaining seconds from an absolute expiry time', () => {
    expect(secondsUntil(10_000, 8_001)).toBe(2)
    expect(secondsUntil(10_000, 10_000)).toBe(0)
    expect(secondsUntil(10_000, 12_000)).toBe(0)
  })

  it('falls back to the configured ttl for invalid deadlines', () => {
    expect(secondsUntil(Number.NaN, 1_000)).toBe(120)
  })

  it('pauses the pairing countdown while connection actions are locked', () => {
    expect(shouldPausePairingCountdown('local', 'connecting', false)).toBe(true)
    expect(shouldPausePairingCountdown('peer', 'checking-code', false)).toBe(true)
    expect(shouldPausePairingCountdown('local', 'waiting', false)).toBe(false)
  })

  it('extends the pairing deadline by the paused duration before resuming', () => {
    expect(resumePairingDeadlineAfterPause(120_000, 20_000, 35_500)).toBe(135_500)
    expect(resumePairingDeadlineAfterPause(120_000, 35_500, 20_000)).toBe(120_000)
  })
})

describe('local room retry helper', () => {
  it('backs off automatic room recreation attempts with a cap', () => {
    expect(localRoomRetryDelay(0)).toBe(2000)
    expect(localRoomRetryDelay(1)).toBe(4000)
    expect(localRoomRetryDelay(3)).toBe(16000)
    expect(localRoomRetryDelay(8)).toBe(30000)
    expect(localRoomRetryDelay(-1)).toBe(2000)
  })

  it('treats a server decision as terminal but keeps retrying an unreachable server', () => {
    expect(isTerminalLocalRoomFailure(new ApiError('No available connection code. Try again shortly.', 'code_exhausted', 503))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('This connection code is already paired.', 'room_full', 409))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('Room or session is not valid.', 'invalid_session', 401))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('deviceId is required.', 'invalid_request', 400))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('Origin is not allowed.', 'origin_not_allowed', 403))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('API endpoint not found.', 'not_found', 404))).toBe(true)
    expect(isTerminalLocalRoomFailure(new ApiError('Too many connection attempts. Try again shortly.', 'rate_limited', 429))).toBe(false)
    expect(isTerminalLocalRoomFailure(new ApiError('Connection code could not be created.', 'http_502', 502))).toBe(false)
    expect(isTerminalLocalRoomFailure(new Error('Signaling service is unreachable.'))).toBe(false)
    expect(isTerminalLocalRoomFailure(new Error('Request timed out.'))).toBe(false)
    expect(isTerminalLocalRoomFailure('boom')).toBe(false)
  })

  it('closes the automatic retry window once the deadline passes', () => {
    expect(localRoomRetryWindowExpired(0, 1_000)).toBe(false)
    expect(localRoomRetryWindowExpired(60_000, 59_999)).toBe(false)
    expect(localRoomRetryWindowExpired(60_000, 60_000)).toBe(true)
    expect(localRoomRetryWindowExpired(60_000, 61_000)).toBe(true)
  })
})

describe('configured default locale helper', () => {
  it('uses an explicit locale path before stored or backend defaults', () => {
    expect(resolveInitialLocale('en', '/zh-CN/faq', '', ['th-TH'])).toEqual({ locale: 'zh-CN', explicit: true, source: 'url' })
    expect(resolveInitialLocale(null, '/pt-BR', '', ['th-TH'])).toEqual({ locale: 'pt-BR', explicit: true, source: 'url' })
  })

  it('still honors the legacy locale query parameter', () => {
    expect(resolveInitialLocale('en', '/', '?locale=zh-CN', ['th-TH'])).toEqual({ locale: 'zh-CN', explicit: true, source: 'url' })
    expect(resolveInitialLocale(null, '/faq', '?locale=pt_BR', ['th-TH'])).toEqual({ locale: 'pt-BR', explicit: true, source: 'url' })
  })

  it('uses the stored locale before browser detection on bare links', () => {
    expect(resolveInitialLocale('fr', '/', '', ['th-TH'])).toEqual({ locale: 'fr', explicit: true, source: 'stored' })
  })

  it('uses the first supported browser locale when no URL or stored locale exists', () => {
    expect(resolveBrowserLocale(['it-IT', 'th-TH', 'en-US'])).toBe('th')
    expect(resolveInitialLocale(null, '/', '', ['it-IT', 'th-TH'])).toEqual({ locale: 'th', explicit: false, source: 'browser' })
    expect(resolveInitialLocale(null, '/', '', ['zh-Hant-HK'])).toEqual({ locale: 'zh-TW', explicit: false, source: 'browser' })
  })

  it('falls back to the configured default only when browser languages have no supported match', () => {
    expect(resolveBrowserLocale(['it-IT'])).toBeNull()
    expect(resolveInitialLocale(null, '/', '', ['it-IT'])).toEqual({ locale: 'en', explicit: false, source: 'fallback' })
    expect(resolveInitialLocale(null, '/', '', ['it-IT'], 'fr-FR')).toEqual({ locale: 'fr', explicit: false, source: 'fallback' })
    expect(resolveInitialLocale(null, '/', '', [], 'pt_BR')).toEqual({ locale: 'pt-BR', explicit: false, source: 'fallback' })
  })

  it('never lets the configured default override a detected browser language', () => {
    expect(resolveInitialLocale(null, '/', '', ['zh-CN'], 'fr')).toEqual({ locale: 'zh-CN', explicit: false, source: 'browser' })
    expect(resolveInitialLocale(null, '/', '', ['en-US'], 'fr')).toEqual({ locale: 'en', explicit: false, source: 'browser' })
  })

  it('keeps the current language in the shareable URL path', () => {
    expect(explicitLocalePath('/', 'th')).toBe('/th')
    expect(explicitLocalePath('/privacy', 'zh-CN')).toBe('/zh-CN/privacy')
    expect(explicitLocalePath('/zh-CN/privacy', 'en')).toBe('/en/privacy')
    expect(explicitLocalePath('/zh-CN', 'zh-TW')).toBe('/zh-TW')
  })

})

describe('connected chat window', () => {
  const t = (key: Parameters<typeof translate>[1]) => translate('en', key)
  const chatFiles: TransferFileItem[] = [
    {
      offer: {
        transferId: 'transfer-chat',
        fileId: 'chat-1',
        name: 'notes.txt',
        mime: 'text/plain',
        size: 32,
        chunkSize: 1_024,
        totalChunks: 1,
      },
      status: 'offered',
      progress: { bytesDone: 0, bytesTotal: 32, bytesPerSecond: 0, secondsRemaining: 0, percent: 0 },
      failure: '',
      atMs: 1_000,
    },
  ]

  it('offers a bounded, dependency-free emoji palette', () => {
    const html = renderToStaticMarkup(createElement(EmojiPicker, { label: translate('en', 'emojiPickerLabel'), onPick: vi.fn() }))

    expect(html).toContain('class="emoji-picker"')
    expect(html).toContain(`aria-label="${translate('en', 'emojiPickerLabel')}"`)
    for (const emoji of emojiOptions) expect(html).toContain(emoji)
    expect(new Set(emojiOptions).size).toBe(emojiOptions.length)
    expect(emojiOptions.length).toBeLessThanOrEqual(48)
  })

  it('labels the emoji action in every locale instead of falling back to English', () => {
    for (const locale of supportedLocales) {
      expect(translate(locale, 'emojiPickerLabel')).not.toBe('emojiPickerLabel')
      if (locale !== 'en') expect(translate(locale, 'emojiPickerLabel')).not.toBe(translate('en', 'emojiPickerLabel'))
    }
  })

  it('splits messages by direction and shows a hint when the conversation is empty', () => {
    const messages: PeerMessageItem[] = [
      { id: 'm1', direction: 'outgoing', text: 'hi there', atMs: 1 },
      { id: 'm2', direction: 'incoming', text: 'hello 👋', atMs: 2 },
    ]
    const html = renderToStaticMarkup(createElement(MessageThread, { t, messages }))

    expect(html).toContain('message-bubble is-outgoing')
    expect(html).toContain('message-bubble is-incoming')
    expect(html).toContain('hi there')
    expect(html).toContain('hello 👋')

    const empty = renderToStaticMarkup(createElement(MessageThread, { t, messages: [] }))
    expect(empty).toContain('No messages yet')
  })

  it('tags chat file groups by sender and hides empty groups', () => {
    const outgoing = renderToStaticMarkup(createElement(FileList, {
      title: 'Local files',
      emptyText: 'No file',
      files: chatFiles,
      t,
      variant: 'outgoing',
      hideWhenEmpty: true,
      actionsLocked: false,
      onCancel: vi.fn(),
    }))
    expect(outgoing).toContain('file-list is-outgoing')
    expect(outgoing).toContain('notes.txt')

    const hidden = renderToStaticMarkup(createElement(FileList, {
      title: 'Peer files',
      emptyText: 'Waiting for peer file',
      files: [],
      t,
      variant: 'incoming',
      hideWhenEmpty: true,
      actionsLocked: false,
      onDownload: vi.fn(),
    }))
    expect(hidden).toBe('')
  })

  it('derives a short avatar label from a device name', () => {
    expect(deviceBadge('PC Chrome')).toBe('PC')
    expect(deviceBadge('Mobile Chrome')).toBe('MC')
    expect(deviceBadge('Chrome')).toBe('CH')
    expect(deviceBadge('手机')).toBe('手')
    expect(deviceBadge('   ')).toBe('?')
    expect(deviceBadge('')).toBe('?')
  })

  it('interleaves file bubbles with text messages by arrival time', () => {
    const messages: PeerMessageItem[] = [
      { id: 'm1', direction: 'outgoing', text: 'first', atMs: 100 },
      { id: 'm3', direction: 'incoming', text: 'last', atMs: 400 },
    ]
    const localFiles: TransferFileItem[] = [{ ...chatFiles[0], atMs: 200 }]
    const remoteFiles: TransferFileItem[] = [{
      ...chatFiles[0],
      atMs: 300,
      offer: { ...chatFiles[0].offer, fileId: 'remote-chat-1', name: 'peer-notes.txt' },
    }]
    const order = buildChatTimeline(messages, localFiles, remoteFiles).map((entry) => {
      if (entry.kind === 'text') return `text:${entry.message.text}`
      if (entry.kind === 'file') return `file:${entry.file.offer.name}`
      return 'recalled'
    })

    // A message sent after a file must land below that file, and the newest entry last.
    expect(order).toEqual(['text:first', 'file:notes.txt', 'file:peer-notes.txt', 'text:last'])
  })

  it('keeps a recalled entry in place as a system note', () => {
    const messages: PeerMessageItem[] = [
      { id: 'm1', direction: 'outgoing', text: 'first', atMs: 100 },
      { id: 'm2', direction: 'incoming', text: 'retracted', atMs: 300, recalled: true },
      { id: 'm3', direction: 'incoming', text: 'last', atMs: 400 },
    ]
    const localFiles: TransferFileItem[] = [{ ...chatFiles[0], atMs: 200, recalled: true }]

    const timeline = buildChatTimeline(messages, localFiles, [])

    expect(timeline.map((entry) => entry.kind)).toEqual(['text', 'recalled', 'recalled', 'text'])
    expect(timeline[1]).toEqual({ kind: 'recalled', key: 'recalled-file-chat-1', atMs: 200, byMe: true })
    expect(timeline[2]).toEqual({ kind: 'recalled', key: 'recalled-text-m2', atMs: 300, byMe: false })
  })

  it('offers recall on an outgoing bubble and leaves incoming bubbles read-only', () => {
    const outgoing = renderToStaticMarkup(createElement(MessageBubble, {
      t,
      message: { id: 'm1', direction: 'outgoing', text: 'mine', atMs: 1 },
      onRecall: vi.fn(),
    }))
    expect(outgoing).toContain('message-recall')
    expect(outgoing).toContain(`aria-label="${translate('en', 'recall')}"`)

    const incoming = renderToStaticMarkup(createElement(MessageBubble, {
      t,
      message: { id: 'm2', direction: 'incoming', text: 'theirs', atMs: 2 },
    }))
    expect(incoming).not.toContain('message-recall')
  })

  it('swaps the file cancel action for a recall action on the sender side', () => {
    const html = renderToStaticMarkup(createElement(FileList, {
      title: 'Local files',
      emptyText: 'No file',
      files: chatFiles,
      t,
      variant: 'outgoing',
      actionsLocked: false,
      onRecall: vi.fn(),
    }))

    expect(html).toContain('file-recall-button')
    expect(html).toContain('data-testid="file-recall"')
    expect(html).not.toContain('file-cancel-button')
  })
})

describe('folder drops', () => {
  const fileEntry = (name: string): DroppedEntry => ({
    isFile: true,
    isDirectory: false,
    file: (onSuccess) => onSuccess(new File(['payload'], name)),
  })
  // readEntries() answers one batch per call and ends with an empty batch.
  const directoryEntry = (batches: DroppedEntry[][]): DroppedEntry => ({
    isFile: false,
    isDirectory: true,
    createReader: () => {
      let batch = 0
      return {
        readEntries: (onSuccess) => {
          onSuccess(batches[batch] ?? [])
          batch += 1
        },
      }
    },
  })

  it('walks folders inside folders and keeps every file it finds', async () => {
    const tree = directoryEntry([[
      fileEntry('top.txt'),
      directoryEntry([[fileEntry('inner.txt')]]),
    ]])

    const { files, truncated } = await collectDroppedFiles([tree])

    expect(files.map((file) => file.name).sort()).toEqual(['inner.txt', 'top.txt'])
    expect(truncated).toBe(false)
  })

  it('keeps reading until a folder reports its last batch', async () => {
    const big = directoryEntry([
      [fileEntry('a.txt')],
      [fileEntry('b.txt')],
      [],
    ])

    const { files } = await collectDroppedFiles([big])

    expect(files.map((file) => file.name)).toEqual(['a.txt', 'b.txt'])
  })

  it('stops at the cap and says that it did', async () => {
    const many = directoryEntry([1, 2, 3, 4].map((index) => [fileEntry(`f${index}.txt`)]))

    const { files, truncated } = await collectDroppedFiles([many], 3)

    expect(files).toHaveLength(3)
    expect(truncated).toBe(true)
  })

  it('skips a file that cannot be read instead of failing the drop', async () => {
    const unreadable: DroppedEntry = {
      isFile: true,
      isDirectory: false,
      file: (_onSuccess, onError) => onError?.(new Error('gone')),
    }

    const { files } = await collectDroppedFiles([unreadable, fileEntry('ok.txt')])

    expect(files.map((file) => file.name)).toEqual(['ok.txt'])
  })

  it('keeps one selection inside the file cap', () => {
    const files = Array.from({ length: 5 }, (_, index) => new File(['x'], `f${index}.txt`))

    expect(limitSelectedFiles(files, 10)).toEqual({ accepted: files, truncated: false })
    expect(limitSelectedFiles(files, 2).accepted.map((file) => file.name)).toEqual(['f0.txt', 'f1.txt'])
    expect(limitSelectedFiles(files, 2).truncated).toBe(true)
    expect(MAX_FOLDER_FILES).toBeGreaterThan(1)
  })

  it('only treats a drop as a folder when one entry is a directory', () => {
    expect(hasDroppedDirectory([fileEntry('loose.txt')])).toBe(false)
    expect(hasDroppedDirectory([fileEntry('loose.txt'), directoryEntry([[]])])).toBe(true)
  })

  it('reads entry handles from the drop and degrades when the browser has none', () => {
    const withEntry = {
      items: [{ webkitGetAsEntry: () => ({ isFile: true, isDirectory: false }) }],
    } as unknown as DataTransfer
    const withoutEntry = { items: [{}] } as unknown as DataTransfer

    expect(droppedEntries(withEntry)).toHaveLength(1)
    expect(droppedEntries(withoutEntry)).toEqual([])
  })

  it('keeps the {count} placeholder in every locale for a capped folder', () => {
    for (const locale of supportedLocales) {
      expect(translate(locale, 'folderTooMany').match(/\{count\}/g), locale).toHaveLength(1)
    }
  })
})
