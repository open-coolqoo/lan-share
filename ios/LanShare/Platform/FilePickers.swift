import SwiftUI
import UniformTypeIdentifiers
import PhotosUI

/// Document picker in `asCopy: false` mode. The delegate fires immediately
/// with the original security-scoped URLs; with `asCopy: true` the system
/// copied the whole file before the callback, which froze the sheet for the
/// entire duration of a large pick. The bytes are materialized by
/// `AppViewModel.materializeDocument` off the main thread instead.
struct DocumentPicker: UIViewControllerRepresentable {
    let onPicked: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPicked: ([URL]) -> Void

        init(onPicked: @escaping ([URL]) -> Void) {
            self.onPicked = onPicked
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPicked(urls)
        }
    }
}

/// Folder picker in `asCopy: false` mode, using the same security-scoped
/// pattern as `DocumentPicker` above: the delegate receives the folder's
/// original URL the moment the user confirms, and nothing is enumerated while
/// the sheet is up.
struct FolderPicker: UIViewControllerRepresentable {
    let onPicked: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPicked: (URL) -> Void

        init(onPicked: @escaping (URL) -> Void) {
            self.onPicked = onPicked
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            // Only the first URL can be the folder: `allowsMultipleSelection`
            // is false and `.folder` is a single item.
            guard let folder = urls.first else { return }
            onPicked(folder)
        }
    }
}

public struct PickedPhoto: Equatable, Sendable {
    public let url: URL
    public let name: String
    public let mime: String

    public init(url: URL, name: String, mime: String) {
        self.url = url
        self.name = name
        self.mime = mime
    }
}

/// Photo/video picker backed by PHPickerViewController (no photo-library
/// permission required for picking). Reports placeholder items the moment the
/// sheet closes and then each loaded file individually, so the Share tab
/// never sits blank while a large photo or video is being copied out.
struct PhotoPicker: UIViewControllerRepresentable {
    /// Fired once, synchronously at dismissal: names for spinner cards.
    let onStarted: ([PreparingSendItem]) -> Void
    let onItemReady: (String, PickedPhoto) -> Void
    let onItemFailed: (String) -> Void
    /// Fired when nothing at all could be loaded.
    let onFailure: (String) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 10
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(
            onStarted: onStarted,
            onItemReady: onItemReady,
            onItemFailed: onItemFailed,
            onFailure: onFailure,
        )
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onStarted: ([PreparingSendItem]) -> Void
        let onItemReady: (String, PickedPhoto) -> Void
        let onItemFailed: (String) -> Void
        let onFailure: (String) -> Void

        init(
            onStarted: @escaping ([PreparingSendItem]) -> Void,
            onItemReady: @escaping (String, PickedPhoto) -> Void,
            onItemFailed: @escaping (String) -> Void,
            onFailure: @escaping (String) -> Void
        ) {
            self.onStarted = onStarted
            self.onItemReady = onItemReady
            self.onItemFailed = onItemFailed
            self.onFailure = onFailure
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard !results.isEmpty else { return }
            let jobs: [(id: String, provider: NSItemProvider)] = results.map {
                (UUID().uuidString, $0.itemProvider)
            }
            onStarted(jobs.map { PreparingSendItem(id: $0.id, name: displayName(for: $0.provider)) })
            let group = DispatchGroup()
            var succeeded = 0
            let lock = NSLock()
            for job in jobs {
                group.enter()
                loadFile(from: job.provider) { photo in
                    defer { group.leave() }
                    if let photo {
                        lock.lock()
                        succeeded += 1
                        lock.unlock()
                        self.onItemReady(job.id, photo)
                    } else {
                        self.onItemFailed(job.id)
                    }
                }
            }
            group.notify(queue: .main) { [onFailure] in
                if succeeded == 0 {
                    onFailure("Unable to read the selected photos.")
                }
            }
        }

        /// Best-effort card title for a pick whose bytes have not arrived yet:
        /// the provider's suggested name, else a type-derived placeholder.
        private func displayName(for provider: NSItemProvider) -> String {
            let typeIdentifiers = provider.registeredTypeIdentifiers
            let preferred = typeIdentifiers.first { UTType($0)?.conforms(to: .movie) == true }
                ?? typeIdentifiers.first { UTType($0)?.conforms(to: .image) == true }
                ?? typeIdentifiers.first
            let type = preferred.flatMap(UTType.init)
            let ext = type?.preferredFilenameExtension
            if let suggested = provider.suggestedName, !suggested.isEmpty {
                if let ext, !suggested.lowercased().hasSuffix(".\(ext.lowercased())") {
                    return "\(suggested).\(ext)"
                }
                return suggested
            }
            if type?.conforms(to: .movie) == true {
                return "Video.\(ext ?? "mov")"
            }
            return "Photo.\(ext ?? "jpg")"
        }

        private func loadFile(from provider: NSItemProvider, completion: @escaping @Sendable (PickedPhoto?) -> Void) {
            let typeIdentifiers = provider.registeredTypeIdentifiers
            let suggestedName = provider.suggestedName
            let preferred = typeIdentifiers.first { UTType($0)?.conforms(to: .movie) == true }
                ?? typeIdentifiers.first { UTType($0)?.conforms(to: .image) == true }
                ?? typeIdentifiers.first
            guard let identifier = preferred, let type = UTType(identifier) else {
                completion(nil)
                return
            }
            provider.loadFileRepresentation(forTypeIdentifier: identifier) { url, error in
                guard let url, error == nil else {
                    DispatchQueue.main.async { completion(nil) }
                    return
                }
                let ext = url.pathExtension.isEmpty
                    ? (type.preferredFilenameExtension ?? "dat")
                    : url.pathExtension
                let destination = FileManager.default.temporaryDirectory
                    .appendingPathComponent("pick-\(UUID().uuidString).\(ext)")
                do {
                    if FileManager.default.fileExists(atPath: destination.path) {
                        try FileManager.default.removeItem(at: destination)
                    }
                    try FileManager.default.copyItem(at: url, to: destination)
                    let name = suggestedName.map { "\($0).\(ext)" } ?? destination.lastPathComponent
                    let photo = PickedPhoto(
                        url: destination,
                        name: name,
                        mime: type.preferredMIMEType ?? "application/octet-stream",
                    )
                    DispatchQueue.main.async { completion(photo) }
                } catch {
                    DispatchQueue.main.async { completion(nil) }
                }
            }
        }
    }
}

/// Share-sheet export for a completed receive.
struct ActivityExportView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = UIViewController()
        DispatchQueue.main.async {
            let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = activity.popoverPresentationController {
                popover.sourceView = controller.view
                popover.sourceRect = CGRect(x: controller.view.bounds.midX, y: controller.view.bounds.maxY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            controller.present(activity, animated: true)
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
