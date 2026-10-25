import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// QR generation via Core Image, mirroring the Web render options
/// (error correction M, small quiet zone, dark `#10201a` on white).
///
/// The returned image carries a scale that makes its *point* size equal `points`
/// square. The Connect self-card shows it at intrinsic size (no `resizable`), so
/// SwiftUI's HStack cannot squash the QR vertically.
public enum PairingQr {
    public static func image(for url: String, points: CGFloat = 86) -> UIImage? {
        guard !url.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(url.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Keep the modules crisp when scaling up (nearest-neighbor sampling).
        let rendered = output.samplingNearest()
        let context = CIContext()
        let modules = rendered.extent.width
        // Render each module at an integer pixel size so the QR stays crisp, targeting
        // `points` * ~4 device pixels for tolerance across 2x/3x screens.
        let modulePixels = max(Int((points * 4) / modules), 1)
        let scaled = rendered.transformed(
            by: CGAffineTransform(scaleX: CGFloat(modulePixels), y: CGFloat(modulePixels))
        )
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        let pixelSize = modules * CGFloat(modulePixels)
        let scale = pixelSize / points
        return UIImage(cgImage: cgImage, scale: scale, orientation: .up)
    }
}
