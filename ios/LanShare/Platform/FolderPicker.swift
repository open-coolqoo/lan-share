import Foundation

/// One folder selection can add at most this many files, so a huge tree cannot
/// flood the chat. Same meaning and value as Android's `MAX_FOLDER_FILES` and
/// the web's `MAX_FOLDER_FILES`.
public let maxFolderFiles = 100

/// One file found under a picked folder, flat and capped at the limit.
public struct FolderTreeFile: Equatable, Sendable {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }
}

/// The files found under one folder, already flat and capped at the limit.
public struct FolderWalkResult: Equatable, Sendable {
    public let files: [FolderTreeFile]
    /// True when the tree held more readable files than the cap (Android
    /// `FolderWalkResult.truncated`, web `truncated`).
    public let truncated: Bool

    public init(files: [FolderTreeFile], truncated: Bool) {
        self.files = files
        self.truncated = truncated
    }
}

/// What the conversation says after a folder pick, if anything.
public enum FolderPickNotice: Equatable, Sendable {
    case empty
    case tooMany

    /// Localization key rendered as a system note in the stream, or `nil`
    /// when the pick needs no notice (Android `FolderPickNotice`).
    public var messageKey: String? {
        switch self {
        case .empty: return "chat_folder_empty"
        case .tooMany: return "chat_folder_too_many"
        }
    }
}

/// Android `folderPickNotice`: a truncated pick with files says 文件较多; a
/// pick with files and no truncation says nothing; a pick with no readable
/// file says 文件夹为空.
public func folderPickNotice(fileCount: Int, truncated: Bool) -> FolderPickNotice? {
    if fileCount > 0 && truncated { return .tooMany }
    if fileCount > 0 { return nil }
    return .empty
}

/// Reads one directory's children. A directory that cannot be listed throws;
/// the walk skips that node and keeps going.
public typealias FolderChildrenProvider = (URL) throws -> [URL]

/// Walks `root` depth-first and returns every readable file it can reach, in
/// a stable pre-order: a directory's files come first (in the order the file
/// manager returns them), then its subdirectories in that same order. The
/// directory structure is not kept — the transfer protocol carries a flat
/// list. Mirrors Android `collectFolderTreeFiles`.
///
/// - A directory whose listing throws is skipped on its own, so one
///   unreadable folder never costs the rest of the selection.
/// - The walk stops once a readable file would exceed `limit`: that overflow
///   is reported as `truncated` and no further branch is opened. A
///   subdirectory seen at the cap is not overflow — it holds no file yet — so
///   exactly 100 files plus an empty subdirectory is a complete pick.
public func collectFolderTreeFiles(
    root: URL,
    limit: Int = maxFolderFiles,
    childrenProvider: FolderChildrenProvider = defaultFolderChildren,
) -> FolderWalkResult {
    var files: [FolderTreeFile] = []
    // `truncated` is only true once a readable file had to be left out, so an
    // unreadable branch is reported as skipped, never as "too many".
    var truncated = false
    var pending: [URL] = [root]
    while let directory = pending.popLast(), !truncated {
        let children = (try? childrenProvider(directory)) ?? []
        // Pre-order: queue subdirectories behind the files of this directory.
        var subdirectories: [URL] = []
        for child in children {
            if isDirectory(child) {
                subdirectories.append(child)
                continue
            }
            // Only a file can be dropped; the cap counts files.
            if files.count >= limit {
                truncated = true
                break
            }
            files.append(FolderTreeFile(url: child))
        }
        // Push in reverse so the first subdirectory is the next one visited.
        pending.append(contentsOf: subdirectories.reversed())
    }
    return FolderWalkResult(files: files, truncated: truncated)
}

/// The production listing: `FileManager.contentsOfDirectory(atPath:)` returns
/// the entries as the provider stored them (no extra sorting), which keeps
/// "the first N" the same files on every pick.
public func defaultFolderChildren(_ directory: URL) throws -> [URL] {
    try FileManager.default.contentsOfDirectory(
        at: directory,
        includingPropertiesForKeys: [.isDirectoryKey],
        options: [],
    )
}

/// Whether `url` is a directory. A file whose type cannot be read is treated
/// as a file, which then fails to read on the send path like any other
/// unreadable pick.
public func isDirectory(_ url: URL) -> Bool {
    (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
}
