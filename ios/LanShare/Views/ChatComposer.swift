import SwiftUI

/// The conversation's input area (design frames L1/L2/L10/D2): ＋ attachment
/// menu, an emoji key, a text field with the project placeholder, and Send.
///
/// The panel (attachment grid or emoji grid) opens directly above the field
/// and is rendered next to it inside the bottom `safeAreaInset`, so it pushes
/// the stream up instead of covering the last bubble.
///
/// Unpaired the field is inert and the emoji key is dimmed (there is nothing
/// to write into), while ＋ keeps working: the picks join the pending queue and
/// go out with the next session. A hint line under the row says why — the
/// Android `ChatComposer` reads the same.
struct ChatComposer: View {
    let theme: ChatTheme
    let brand: Color
    @Binding var draft: String
    let enabled: Bool
    /// Separate from `enabled`: attachments are queueable while unpaired.
    var attachmentsEnabled = true
    /// The unpaired sentence under the row; `nil` paired.
    var hint: String? = nil
    /// A pick that could not be read, shown in place of the hint.
    var pickFailure: String? = nil
    /// Opened by the view, not by the keyboard: tapping the field while a
    /// panel is up closes the panel, as on iOS messaging apps.
    let focused: FocusState<Bool>.Binding
    let panel: ChatPanel?
    let onTogglePanel: (ChatPanel) -> Void
    let onFieldFocus: () -> Void
    let onEmoji: (String) -> Void
    let onSend: () -> Void
    /// 相册 reuses the photo picker, 文件 the document picker, 文件夹 the native
    /// folder picker; all three land on the same send path.
    let onPickPhotos: () -> Void
    let onPickFiles: () -> Void
    let onPickFolder: () -> Void

    private var canSend: Bool { chatSendEnabled(draft) && enabled }

    /// The unpaired state keeps every control in place and dims what is inert,
    /// so nothing shifts when the session starts.
    private static let disabledOpacity = 0.45

    var body: some View {
        if #available(iOS 26.0, *) {
            content
                // The Liquid Glass strip replaces the opaque input area: the
                // stream shows through and blurs beneath it (HIG: a composer
                // is a bar, the functional layer glass is for).
                .liquidGlass(in: Rectangle())
                .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        } else {
            content
                .background(theme.inputArea)
                .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            if #available(iOS 26.0, *) {
                // Glass carries the composer's top edge; no hairline.
                EmptyView()
            } else {
                Rectangle().fill(theme.hairline).frame(height: 0.5)
            }
            switch panel {
            case .attachments:
                attachmentPanel
            case .emoji:
                emojiPanel
            case nil:
                EmptyView()
            }
            HStack(alignment: .bottom, spacing: 4) {
                toolButton(systemImage: "plus", label: "Drop files here, or choose files.", selected: panel == .attachments, enabled: attachmentsEnabled) {
                    onTogglePanel(.attachments)
                }
                toolButton(systemImage: "face.smiling", label: "chat_emoji_picker", selected: panel == .emoji, enabled: enabled) {
                    onTogglePanel(.emoji)
                }

                TextField(text: $draft, prompt: Text(placeholder)) {
                    Text("chat_message_placeholder")
                }
                .lineLimit(1 ... 4)
                .font(.body)
                .foregroundStyle(theme.inputInk)
                .focused(focused)
                .submitLabel(.send)
                .onSubmit { if canSend { onSend() } }
                .onChange(of: focused.wrappedValue) { _, isFocused in
                    if isFocused { onFieldFocus() }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 10)
                .frame(minHeight: 44)
                .background(theme.inputField, in: RoundedRectangle(cornerRadius: 8))
                .disabled(!enabled)

                sendButton
                .disabled(!canSend)
                // Design §2 L1: the same button at 55%, never a grey swap; an
                // unpaired session dims it alike, since sending is impossible.
                .opacity(enabled ? chatSendOpacity(draft: draft) : Self.disabledOpacity)
                .accessibilityLabel(Text("chat_send"))
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 8)

            if let note = pickFailure ?? hint {
                Text(LocalizedStringKey(note))
                    .font(.caption2)
                    .foregroundStyle(pickFailure == nil ? theme.meta : theme.errorInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 8)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("chat-composer-hint")
            }
        }
        // The controls line stays as wide as the stream above it; the glass
        // surface behind still spans the canvas (see `body`).
        .adaptiveColumn(AdaptiveLayout.conversationWidth)
        // The composer is the tab content's bottom surface: the shell is inset
        // by the keyboard, so it sits directly above the tab bar with no extra
        // padding of its own (see `BottomTabBar`). The background (glass on
        // iOS 26, the opaque `inputArea` below) is applied by `body`.
    }

    /// 发送 — the system tinted-glass prominent action on iOS 26, the drawn
    /// brand capsule below.
    @ViewBuilder
    private var sendButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: onSend) {
                Text("chat_send")
                    .font(.subheadline.weight(.bold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.glassProminent)
            .tint(brand)
            .accessibilityLabel(Text("chat_send"))
        } else {
            Button(action: onSend) {
                Text("chat_send")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .background(brand, in: RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("chat_send"))
        }
    }

    /// A blank field always shows the placeholder; the disabled session says
    /// why it is inactive rather than showing an editable empty box.
    private var placeholder: String {
        enabled
            ? String(localized: "chat_message_placeholder")
            : String(localized: "chat_composer_unpaired_hint")
    }

    private func toolButton(
        systemImage: String,
        label: LocalizedStringKey,
        selected: Bool,
        enabled: Bool = true,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(theme.iconInk)
                .frame(width: 44, height: 44)
                .background(selected ? theme.tileFill : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : Self.disabledOpacity)
        .accessibilityLabel(Text(label))
    }

    // MARK: Panels

    private var attachmentPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 20) {
                attachmentTile(systemImage: "photo.on.rectangle", label: "Gallery", action: onPickPhotos)
                attachmentTile(systemImage: "doc", label: "Files", action: onPickFiles)
                attachmentTile(systemImage: "folder", label: "chat_attach_folder", action: onPickFolder)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)
        }
        .padding(.bottom, 12)
    }

    private func attachmentTile(
        systemImage: String,
        label: LocalizedStringKey,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(brand)
                    .frame(width: 54, height: 54)
                    .background(theme.tileFill, in: RoundedRectangle(cornerRadius: 10))
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(theme.meta)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(minWidth: 56)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }

    private var emojiPanel: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)
        return LazyVGrid(columns: columns, spacing: 0) {
            ForEach(chatEmojiPalette, id: \.self) { emoji in
                Button { onEmoji(emoji) } label: {
                    Text(emoji)
                        .font(.system(size: 22))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(emoji))
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }
}

/// Which panel sits above the field, if any. Only one can be open: ＋ and
/// the emoji key replace each other, and the keyboard is the third state.
enum ChatPanel: Equatable, Sendable {
    case attachments
    case emoji
}

extension View {
    /// Folder picker in `asCopy: false` mode; the walk, the security scope and
    /// the copies are handled by `AppViewModel.enqueueFolderPick`.
    func chatFolderPicker(isPresented: Binding<Bool>, model: AppViewModel) -> some View {
        sheet(isPresented: isPresented) {
            FolderPicker { folder in
                model.enqueueFolderPick(folder)
            }
        }
    }

    /// Photo picker, wired to the same preparation pipeline as the Share tab.
    func chatPhotoPicker(isPresented: Binding<Bool>, model: AppViewModel) -> some View {
        sheet(isPresented: isPresented) {
            PhotoPicker { placeholders in
                model.beginPhotoPreparation(placeholders)
            } onItemReady: { id, photo in
                model.finishPhotoPreparation(id: id, photo: photo)
            } onItemFailed: { id in
                model.finishPhotoPreparation(id: id, photo: nil)
            } onFailure: { message in
                model.reportError(message)
            }
        }
    }

    /// Document picker in `asCopy: false` mode; bytes are materialized by
    /// `AppViewModel.enqueueDocumentPicks`.
    func chatDocumentPicker(isPresented: Binding<Bool>, model: AppViewModel) -> some View {
        sheet(isPresented: isPresented) {
            DocumentPicker { urls in
                model.enqueueDocumentPicks(urls)
            }
        }
    }
}
