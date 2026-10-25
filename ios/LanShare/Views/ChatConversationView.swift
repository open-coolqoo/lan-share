import SwiftUI

/// The conversation, rendered inside the 传输 tab (product decision 2026-10-20:
/// Android's transfer tab *is* the chat). It holds no navigation state and
/// draws no bottom bar — the shell draws 连接 / 传输 / 设置 in every state, so
/// the top bar carries no ‹ anymore; the peer label and ⓘ open 连接详情.
///
/// Paired it is the message stream; unpaired (or between sessions) the same
/// page shows one chat-style empty state with 前往连接 and a composer that
/// cannot send text. Attachments stay usable while unpaired because the pending
/// queue really accepts them: every pick goes through
/// `AppViewModel.enqueueSendItems`, which appends before it tries to flush, so
/// the files wait for the next session instead of being dropped (the guarantee
/// Android's `enqueueSharedUris` gives).
///
/// Layout: top bar, then the stream (bottom-anchored, scrolls) and the composer
/// inside the bottom `safeAreaInset`. The composer is a full-width bottom
/// surface inside the tab content; when the keyboard opens the root stack is
/// inset by it, so the composer ends up directly above the tab bar with no
/// extra padding of its own (see `BottomTabBar`).
struct ChatConversationView: View {
    @Bindable var model: AppViewModel
    /// 前往连接 on the unpaired empty state.
    let onGoToConnect: () -> Void
    /// The sheet's 设置 row: the shell selects the Settings tab.
    var onOpenSettings: (() -> Void)? = nil
    /// The 断开连接 confirmation was accepted. The shell moves to 连接 once the
    /// session is really gone.
    var onDisconnect: (() -> Void)? = nil

    @State private var draft = ""
    @State private var panel: ChatPanel?
    @State private var showPhotoPicker = false
    @State private var showDocumentPicker = false
    @State private var showFolderPicker = false
    @State private var actionEntry: ChatTimelineEntry?
    /// The entry whose action sheet is up (long press on a text bubble).
    @State private var menuEntry: ChatTimelineEntry?
    @State private var showDetails = false
    /// The code the sheet shows. Held from the moment the sheet opens: a
    /// transient reconnect drops `AppState.pairedCode` while the session (and
    /// the code the two devices share) survives, and the sheet must not lose
    /// its code row or close itself mid-reconnect.
    @State private var detailsCode: String?
    /// The sheet's own two-step 断开连接 confirmation (the stream's recall
    /// dialog is separate).
    @State private var confirmingDisconnect = false
    @State private var exportItem: ReceivedFileInfo?
    @FocusState private var fieldFocused: Bool

    private var state: AppState { model.state }
    private var theme: ChatTheme { ChatTheme(dark: state.darkTheme) }
    private var brand: Color { Theme.primary(state.siteConfig) }
    private var reconnecting: Bool { state.transientTransportReconnecting && state.joinedRoom != nil }
    private var peerName: String {
        let label = state.peerDeviceLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        return label.isEmpty ? String(localized: "Web browser") : label
    }

    /// Paired: the channel is open, or a transient reconnect keeps the session.
    /// Unpaired is the chat-style empty state with an inert text field.
    private var paired: Bool {
        chatComposerEnabled(
            channelReady: state.channelReady,
            reconnecting: reconnecting,
            hasRoom: state.joinedRoom != nil,
        )
    }

    var body: some View {
        Group {
            if !paired {
                unpairedStream
            } else {
                pairedStream
            }
        }
        // The peer bar is a safe-area inset, not a stack slot, so the stream
        // scrolls beneath it — on iOS 26 the bar's Liquid Glass blurs what
        // passes under, the way the system conversation screens do. The
        // composer below is the same kind of inset.
        .safeAreaInset(edge: .top, spacing: 0) {
            topBar
        }
        .background(theme.canvas)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer
        }
        .environment(\.locale, Locale(identifier: state.locale))
        .chatPhotoPicker(isPresented: $showPhotoPicker, model: model)
        .chatDocumentPicker(isPresented: $showDocumentPicker, model: model)
        .chatFolderPicker(isPresented: $showFolderPicker, model: model)
        .sheet(item: $exportItem) { info in
            ActivityExportView(url: info.url)
        }
        .sheet(isPresented: detailsOpenBinding, onDismiss: { showDetails = false }) {
            ChatDetailsSheet(
                model: model,
                details: detailsModel,
                locale: state.locale,
                onOpenSettings: {
                    showDetails = false
                    onOpenSettings?()
                },
                onDisconnect: confirmDisconnect,
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            "Disconnect?",
            isPresented: $confirmingDisconnect,
            titleVisibility: .visible,
        ) {
            Button("Disconnect", role: .destructive) {
                model.disconnect()
                // The shell lands on 连接 once the session is gone.
                onDisconnect?()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Disconnecting will interrupt any active transfers.")
        }
        // A real disconnect empties the room, so the sheet has nothing left
        // to show; it closes itself rather than lingering over the page.
        .onChange(of: detailsAvailable) { _, available in
            if !available { showDetails = false }
        }
        .onChange(of: showDetails) { _, open in
            if !open { detailsCode = nil }
        }
        .onChange(of: paired) { _, nowPaired in
            // The unpaired page is a fresh start: no draft or panel survives
            // the switch to the stream (or back).
            if !nowPaired {
                draft = ""
                panel = nil
                fieldFocused = false
            }
        }
        .confirmationDialog(
            "chat_recall",
            isPresented: Binding(
                get: { actionEntry != nil },
                set: { if !$0 { actionEntry = nil } },
            ),
            titleVisibility: .visible,
            presenting: actionEntry,
        ) { entry in
            Button("chat_recall", role: .destructive) {
                recall(entry)
                actionEntry = nil
            }
            Button("Cancel", role: .cancel) { actionEntry = nil }
        }
        // The long-press menu on a text bubble. The entries come from the
        // tested rule, so the view only maps them onto buttons; with no title
        // the system offers the action sheet with Cancel (and the accessibility
        // equivalent) by itself.
        .confirmationDialog(
            "",
            isPresented: longPressMenuBinding,
            titleVisibility: .hidden,
            presenting: menuEntry,
        ) { entry in
            ForEach(chatLongPressActions(entry: entry), id: \.self) { action in
                switch action {
                case .copy:
                    Button("Copy") { copy(entry) }
                case .recall:
                    Button("chat_recall", role: .destructive) {
                        recall(entry)
                        menuEntry = nil
                    }
                }
            }
        }
    }

    // MARK: Top bar

    /// The peer label + live dot + ⓘ. No ‹: the conversation is a tab now, and
    /// the bar below is how the user leaves it. Unpaired the title is a neutral
    /// 未连接 and the status dot is the grey one.
    @ViewBuilder
    private var topBar: some View {
        if #available(iOS 26.0, *) {
            peerBar
                // The Liquid Glass strip replaces the opaque bar + hairline:
                // the canvas shows through and the stream blurs underneath.
                .liquidGlass(in: Rectangle())
        } else {
            peerBar
                .background(theme.barBackground)
                .overlay(alignment: .bottom) {
                    Rectangle().fill(theme.hairline).frame(height: 0.5)
                }
        }
    }

    private var peerBar: some View {
        Button(action: openDetails) {
            HStack(spacing: 6) {
                Text(LocalizedStringKey(chatTopBarTitle(status, peerName: peerName)))
                    .font(.headline)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                statusDot
                Spacer(minLength: 0)
                Image(systemName: "info.circle")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(theme.ink)
                    .frame(width: 44, height: 44)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!detailsAvailable)
        .accessibilityLabel(Text("chat_details_title"))
        .accessibilityValue(Text(verbatim: statusWord))
        // Grid: mirrors the shell top bar (frame ≤ `wideWidth`, 16pt inset,
        // centred) so both bars' content lines up with the page cards; the
        // glass strip behind still spans the canvas.
        .frame(maxWidth: AdaptiveLayout.wideWidth)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        // Open-question 6: the bar stays one line at very large sizes instead
        // of squeezing the info target off screen.
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }

    /// 连接详情 needs a paired session and a code to show; unpaired there is
    /// nothing to open and ⓘ stays inert.
    private var detailsAvailable: Bool {
        chatDetailsAvailable(conversationVisible: paired, sessionCode: state.pairedCode)
    }

    private var detailsModel: ChatDetailsModel {
        chatDetailsModel(
            state: state,
            reconnecting: state.transientTransportReconnecting,
            pairedCode: detailsCode ?? state.pairedCode,
            webBrowserLabel: String(localized: "Web browser"),
        )
    }

    /// A real disconnect empties the room, so the sheet closes itself rather
    /// than lingering over the home page (Android `LaunchedEffect`).
    private var detailsOpenBinding: Binding<Bool> {
        Binding(get: { showDetails }, set: { showDetails = $0 })
    }

    /// The entry's long-press menu (design L8): 复制 on own and peer text, plus
    /// 撤回 (destructive) on an own live entry. A file bubble has no menu; its
    /// recall stays the confirm dialog `actionEntry` shows.
    private var longPressMenuBinding: Binding<Bool> {
        Binding(get: { menuEntry != nil }, set: { if !$0 { menuEntry = nil } })
    }

    private var status: ChatConnectionStatus {
        chatConnectionStatus(
            channelReady: state.channelReady,
            reconnecting: reconnecting,
            hasRoom: state.joinedRoom != nil,
        )
    }

    private var statusWord: String {
        switch status {
        case .direct: return String(localized: "Connected")
        case .reconnecting: return String(localized: "chat_reconnecting")
        case .disconnected: return String(localized: "chat_peer_disconnected")
        }
    }

    private var statusDot: some View {
        Circle()
            .fill(status == .direct ? theme.statusGreen : (status == .reconnecting ? theme.statusAmber : theme.meta))
            .frame(width: 7, height: 7)
            .padding(.trailing, 2)
            .accessibilityHidden(true)
    }

    // MARK: Stream

    /// Unpaired: exactly one notice, the connect action, and nothing stale —
    /// no timeline, no pending row, no failure banner (Android
    /// `ChatUnpairedStream`). It is also what the page falls back to after a
    /// real disconnect, so an old session's bubbles never reappear.
    private var unpairedStream: some View {
        VStack(spacing: 14) {
            ChatSystemNote(
                theme: theme,
                text: String(localized: "chat_unpaired_stream"),
            )
            SecondaryButton(
                label: "Go to Connect",
                primary: brand,
                fullWidth: false,
                action: onGoToConnect,
            )
            .frame(minHeight: 44)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-unpaired-stream")
    }

    private var pairedStream: some View {
        let timeline = ChatTimeline.build(
            messages: state.chatMessages,
            local: state.localTransfers,
            peer: state.peerTransfers,
        )
        return stream(timeline)
    }

    @ViewBuilder
    private func stream(_ timeline: [ChatTimelineEntry]) -> some View {
        if timeline.isEmpty, isEmptyStream {
            emptyStream
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 14) {
                        ForEach(Array(timeline.enumerated()), id: \.element.key) { index, entry in
                            row(entry, previous: index > 0 ? timeline[index - 1] : nil)
                                .id(entry.key)
                        }
                        if !state.preparingSendItems.isEmpty {
                            ChatPreparingRow(theme: theme, items: state.preparingSendItems)
                                .id("chat-preparing")
                        }
                        if !state.pendingSendItems.isEmpty {
                            pendingRow
                                .id("chat-pending")
                        }
                        if !state.chatFailure.isEmpty {
                            ErrorBanner(message: failureText)
                                .id("chat-failure")
                        }
                        if let notice = state.folderPickNotice {
                            folderNoticeRow(notice)
                                .id("chat-folder-notice")
                        }
                        if status != .direct {
                            ChatSystemNote(
                                theme: theme,
                                text: statusWord,
                                warning: status == .reconnecting,
                            )
                            .id("chat-session-note")
                        }
                        Color.clear.frame(height: 1).id(Self.tailAnchor)
                    }
                    .padding(.vertical, 18)
                    // The conversation shares the connect page's grid: the
                    // stream column's edges line up with the cards, the two
                    // bars and the composer on a wide canvas.
                    .frame(maxWidth: AdaptiveLayout.conversationWidth)
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                // Anchor a short conversation to the bottom, the way the web stream does
                // (`.chat-timeline { margin-top: auto }`) and the design requires (design
                // doc §1, "底部锚定"): the newest entry sits next to the composer and the
                // stream grows upward. Without it the first message pinned to the top of
                // an otherwise empty screen.
                .defaultScrollAnchor(.bottom)
                .onChange(of: streamVersion(timeline), initial: true) { _, _ in
                    scrollToEnd(proxy)
                }
                .onChange(of: fieldFocused) { _, focused in
                    if focused { scrollToEnd(proxy) }
                }
            }
        }
    }

    private static let tailAnchor = "chat-tail"

    /// Only the "nothing to say" case gets the centred hint: a failure, a
    /// disconnect or a pending queue are real content and keep the stream.
    private var isEmptyStream: Bool {
        state.chatFailure.isEmpty
            && status == .direct
            && state.pendingSendItems.isEmpty
            && state.preparingSendItems.isEmpty
            && state.folderPickNotice == nil
    }

    private var emptyStream: some View {
        VStack(spacing: 8) {
            ChatSystemNote(theme: theme, text: String(localized: "chat_session_established"))
            Text("chat_messages_empty")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(theme.ink)
            Text("chat_empty_hint")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(theme.meta)
        }
        .padding(.horizontal, 24)
    }

    @ViewBuilder
    private func row(_ entry: ChatTimelineEntry, previous: ChatTimelineEntry?) -> some View {
        switch entry {
        case .recalled(_, _, let byMe):
            ChatSystemNote(
                theme: theme,
                text: String(localized: byMe ? "chat_recall_self" : "chat_recall_peer"),
            )
        case .text(let message):
            ChatBubbleRow(
                theme: theme,
                incoming: !message.outgoing,
                avatar: deviceBadge(message.outgoing ? state.localDeviceLabel : peerName),
                senderName: senderName(entry, previous: previous),
                accessibilityText: chatBubbleAccessibilityText(entry: entry, peerName: peerName),
                longPress: longPressAction(entry, isFile: false),
            ) {
                ChatTextBubble(theme: theme, outgoing: message.outgoing, text: message.text)
            }
        case .file(let card, let incoming):
            let action = chatFileAction(card: card, incoming: incoming)
            ChatBubbleRow(
                theme: theme,
                incoming: incoming,
                avatar: deviceBadge(incoming ? peerName : state.localDeviceLabel),
                senderName: senderName(entry, previous: previous),
                accessibilityText: chatBubbleAccessibilityText(entry: entry, peerName: peerName),
                longPress: longPressAction(entry, isFile: true),
            ) {
                ChatFileBubble(
                    theme: theme,
                    card: card,
                    incoming: incoming,
                    statusText: fileStatusText(card),
                    action: action,
                    onAction: { perform(action, on: entry) },
                )
            }
        }
    }

    private func senderName(_ entry: ChatTimelineEntry, previous: ChatTimelineEntry?) -> String? {
        chatSenderLabel(entry: entry, previous: previous) == nil ? nil : peerName
    }

    /// Opens the menu for a text bubble; a file bubble keeps its Recall
    /// confirmation, since a file has no clipboard form.
    private func longPressAction(_ entry: ChatTimelineEntry, isFile: Bool) -> (() -> Void)? {
        if isFile { return recallAction(entry) }
        guard !chatLongPressActions(entry: entry).isEmpty else { return nil }
        return { menuEntry = entry }
    }

    private func recallAction(_ entry: ChatTimelineEntry) -> (() -> Void)? {
        chatLongPressRecallTarget(entry: entry) == nil ? nil : { actionEntry = entry }
    }

    /// `2.4 MB · 就绪`'s status half. The existing strings are the project's
    /// copy; each status maps onto one of them.
    private func fileStatusText(_ card: TransferCardState) -> String {
        switch card.status {
        case TransferCardStatus.readyToAccept:
            return String(localized: "Ready")
        case TransferCardStatus.transferring:
            return String(format: String(localized: "chat_file_sending"), card.progressPercent ?? 0)
        case TransferCardStatus.receivingFromWeb:
            return String(format: String(localized: "chat_file_receiving"), card.progressPercent ?? 0)
        case TransferCardStatus.waitingForAcceptance:
            return String(localized: "Waiting for Web acceptance.")
        case TransferCardStatus.sentToWeb:
            return String(localized: "chat_file_sent")
        case TransferCardStatus.savedOnIOS:
            return String(localized: "chat_file_saved")
        case TransferCardStatus.canceled:
            return String(localized: "Canceled")
        default:
            if card.status.hasPrefix(TransferCardStatus.rejectedPrefix) {
                return String(localized: "chat_file_rejected")
            }
            if card.status.hasPrefix(TransferCardStatus.errorPrefix) {
                let reason = String(card.status.dropFirst(TransferCardStatus.errorPrefix.count))
                return String(format: String(localized: "chat_file_failed"), reason)
            }
            return ""
        }
    }

    /// 这个文件夹里没有可发送的文件 / 文件较多，只发送了前 100 个 — a tap
    /// dismisses it, as on Android. The text is already localized by
    /// `chatFolderNoticeText`, because the cap message carries a `%1$d`.
    private func folderNoticeRow(_ notice: FolderPickNotice) -> some View {
        Button {
            model.dismissFolderPickNotice()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "folder")
                    .foregroundStyle(theme.meta)
                Text(verbatim: chatFolderNoticeText(notice))
                    .font(.caption)
                    .foregroundStyle(theme.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(theme.meta)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(theme.peerBubble, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var pendingRow: some View {        HStack(spacing: 10) {
            Text(String(format: String(localized: "chat_pending_files"), state.pendingSendItems.count))
                .font(.caption.weight(.bold))
                .foregroundStyle(theme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            if state.channelReady {
                Button("chat_pending_send_all") {
                    model.flushQueuedSendItems()
                }
                .font(.caption.weight(.bold))
                .foregroundStyle(brand)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(theme.peerBubble, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }

    /// [AppState.chatFailure] carries a code, never UI copy; the screen
    /// translates it (Android `chatFailureText`).
    private var failureText: String {
        state.chatFailure == chatChannelNotReady
            ? String(localized: "chat_channel_not_ready")
            : state.chatFailure
    }

    /// Everything that can change the stream's height, so a status update on
    /// an older bubble also brings the end back into view.
    private func streamVersion(_ timeline: [ChatTimelineEntry]) -> String {
        (timeline.map(\.key)
            + chatStreamTailNoteKeys(failure: state.chatFailure, channelReady: status == .direct)
            + ["prepare-\(state.preparingSendItems.count)", "pending-\(state.pendingSendItems.count)"]
            + [state.folderPickNotice?.messageKey ?? ""])
            .joined(separator: "|")
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        // One main-queue hop: the new tail anchor is not placed at the moment
        // the change fires.
        DispatchQueue.main.async {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(Self.tailAnchor, anchor: .bottom)
            }
        }
    }

    // MARK: Actions

    /// The one slot's action, straight from `chatFileAction`.
    private func perform(_ action: ChatFileAction?, on entry: ChatTimelineEntry) {
        guard let action, case .file(let card, let incoming) = entry else { return }
        switch action {
        case .accept:
            model.acceptIncoming(fileId: card.fileId)
        case .cancel:
            // Abort the incoming transfer; the bubble stays and turns 已取消.
            guard incoming else { return }
            model.cancelIncomingTransfer(fileId: card.fileId)
        case .openDownloads:
            // The export sheet IS the "open downloads" step for this file; the
            // received file carries its saved URL.
            exportItem = state.receivedFiles.first { $0.fileId == card.fileId }
                ?? state.receivedFiles.first { $0.name == card.title }
        case .recall:
            guard !incoming else { return }
            // No second confirmation: the design's recall slot acts directly
            // once the user is already in the bubble's confirm dialog path.
            model.recallChatEntry(target: .file, targetId: card.fileId)
        }
    }

    private func recall(_ entry: ChatTimelineEntry) {
        guard let target = chatLongPressRecallTarget(entry: entry),
              let id = chatLongPressRecallId(entry: entry) else { return }
        model.recallChatEntry(target: target, targetId: id)
        UIAccessibility.post(notification: .announcement, argument: String(localized: "chat_recall_self"))
    }

    /// 复制 on a text bubble, through the model so the pasteboard write lives
    /// in one place with the pairing code's.
    private func copy(_ entry: ChatTimelineEntry) {
        guard let text = chatCopyableText(entry: entry), model.copyChatText(text) else { return }
        UIAccessibility.post(notification: .announcement, argument: String(localized: "Copy"))
    }

    private func submit() {
        // The unpaired composer shows an inert field, but the rule holds here
        // too: no text leaves this page without an open channel.
        guard paired, chatSendEnabled(draft) else { return }
        model.sendChatText(draft)
        draft = ""
    }

    /// The top bar's ⓘ: the sheet lists what to do next, so no composer panel
    /// or keyboard stays under it (Android `openDetails`).
    private func openDetails() {
        guard detailsAvailable else { return }
        detailsCode = state.pairedCode
        panel = nil
        fieldFocused = false
        showDetails = true
    }

    /// 断开连接 is two steps: the sheet's own confirmation asks, then the
    /// session ends through the same `model.disconnect()` the home page uses.
    private func confirmDisconnect() {
        showDetails = false
        // A sheet cannot be dismissed and be replaced by a confirmation
        // dialog in the same frame.
        Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            confirmingDisconnect = true
        }
    }

    // MARK: Composer

    private var composer: some View {        ChatComposer(
            theme: theme,
            brand: brand,
            draft: $draft,
            enabled: paired,
            attachmentsEnabled: paired || chatAttachmentsEnabledWhileUnpaired,
            hint: chatComposerHint(paired: paired),
            // A pick the Files provider could not read used to surface on the
            // Connect tab as `roomError`; with the conversation as the page that
            // takes the pick, it is reported here instead.
            pickFailure: state.roomError.hasPrefix("Unable to read ") ? state.roomError : nil,
            focused: $fieldFocused,
            panel: panel,
            onTogglePanel: { toggled in
                if panel == toggled {
                    panel = nil
                    fieldFocused = paired
                } else {
                    panel = toggled
                    fieldFocused = false
                }
            },
            onFieldFocus: { panel = nil },
            onEmoji: { draft = appendEmoji(draft, $0) },
            onSend: submit,
            onPickPhotos: {
                panel = nil
                showPhotoPicker = true
            },
            onPickFiles: {
                panel = nil
                showDocumentPicker = true
            },
            onPickFolder: {
                panel = nil
                showFolderPicker = true
            },
        )
    }
}

extension ReceivedFileInfo: Identifiable {
    public var id: String { fileId }
}
