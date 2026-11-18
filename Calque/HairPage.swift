import CoreGraphics
import PDFKit
import UIKit

/// Draws sheets through PDFium with every line at hairline width while hairlines are on;
/// otherwise PDFKit draws them as usual.
final class HairRenderer {
    private let handle: UnsafeMutableRawPointer
    /// Line width on screen, in pixels.
    let hairPx: Float

    init?(url: URL, password: String?) {
        guard let h = url.withUnsafeFileSystemRepresentation({ path -> UnsafeMutableRawPointer? in
            guard let path = path else { return nil }
            return password.map { pw in pw.withCString { hair_open(path, $0) } } ?? hair_open(path, nil)
        }) else { return nil }
        handle = h
        hairPx = Float(max(1.2, UIScreen.main.scale * 0.7))
    }

    deinit { hair_close(handle) }

    /// An image of a part of a page (PDF user space rectangle) at `scale` pixels per point.
    func image(page: Int, rect: CGRect, scale: CGFloat) -> CGImage? {
        let w = Int(ceil(rect.width * scale)), h = Int(ceil(rect.height * scale))
        guard w > 0, h > 0, w * h <= 40_000_000 else { return nil }
        let stride = w * 4
        guard let data = CFDataCreateMutable(nil, stride * h) else { return nil }
        CFDataSetLength(data, stride * h)
        let ok = hair_render(handle, Int32(page), CFDataGetMutableBytePtr(data), Int32(w), Int32(h), Int32(stride),
                             Double(rect.minX), Double(rect.minY), Double(rect.maxX), Double(rect.maxY),
                             Double(scale), hairPx)
        guard ok == 1, let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}

final class HairPage: PDFPage {
    /// Set by the open viewer; nil when hairlines are off.
    static var renderer: HairRenderer?

    override func draw(with box: PDFDisplayBox, to context: CGContext) {
        guard let r = HairPage.renderer, let ref = pageRef else {
            super.draw(with: box, to: context)
            return
        }
        context.saveGState()
        defer { context.restoreGState() }
        // from here on, drawing is in the page's own PDF user space
        transform(context, for: box)
        let region = context.boundingBoxOfClipPath.intersection(bounds(for: box))
        guard !region.isNull, region.width > 0, region.height > 0 else { return }
        let ctm = context.ctm
        let scale = sqrt(abs(ctm.a * ctm.d - ctm.b * ctm.c))
        guard scale > 0, let img = r.image(page: ref.pageNumber - 1, rect: region, scale: scale) else {
            context.restoreGState()
            context.saveGState()
            super.draw(with: box, to: context)
            return
        }
        context.interpolationQuality = .none
        context.draw(img, in: region)
    }
}
