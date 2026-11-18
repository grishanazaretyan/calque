import CoreGraphics
import Foundation

/// Line segments of one sheet in a coarse grid, to find the nearest end point or edge quickly.
/// Coordinates are the sheet's PDF user space (points, y up), the same space PDFKit uses.
final class SnapIndex {
    static let maxSegments = 1_500_000
    private static let grid = 96

    private(set) var seg: [Float] = []
    private var cw: Float = 1, ch: Float = 1, ox: Float = 0, oy: Float = 0
    private var start: [Int32] = [], items: [Int32] = []

    var count: Int { seg.count / 4 }

    init(segments: [Float], bounds: CGRect) {
        seg = segments
        build(bounds)
    }

    private func cell(_ x: Float, _ y: Float) -> Int {
        let g = SnapIndex.grid
        let cx = max(0, min(g - 1, Int((x - ox) / cw))), cy = max(0, min(g - 1, Int((y - oy) / ch)))
        return cy * g + cx
    }

    private func build(_ b: CGRect) {
        let g = SnapIndex.grid
        ox = Float(b.minX); oy = Float(b.minY)
        cw = max(1e-3, Float(b.width) / Float(g))
        ch = max(1e-3, Float(b.height) / Float(g))
        var counts = [Int32](repeating: 0, count: g * g + 1)
        let n = count
        func visit(_ s: Int, _ body: (Int) -> Void) {
            let i = s * 4
            let x0 = seg[i], y0 = seg[i + 1], x1 = seg[i + 2], y1 = seg[i + 3]
            let len = hypot(x1 - x0, y1 - y0)
            let steps = min(4 * g, Int((len / (min(cw, ch) * 0.5)).rounded(.up)))
            var last = -1
            for k in 0...max(0, steps) {
                let t: Float = steps == 0 ? 0 : Float(k) / Float(steps)
                let c = cell(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
                if c == last { continue }
                last = c
                body(c)
            }
        }
        for s in 0..<n { visit(s) { counts[$0 + 1] += 1 } }
        for c in 0..<(g * g) { counts[c + 1] += counts[c] }
        start = counts
        var cursor = Array(counts[0..<(g * g)])
        items = [Int32](repeating: 0, count: Int(counts[g * g]))
        for s in 0..<n {
            visit(s) { c in
                items[Int(cursor[c])] = Int32(s)
                cursor[c] += 1
            }
        }
    }

    /// Nearest end point within r, else the nearest point on an edge. (point, true if an end point)
    func query(_ p: CGPoint, radius: CGFloat) -> (CGPoint, Bool)? {
        if count == 0 { return nil }
        let x = Float(p.x), y = Float(p.y)
        let g = SnapIndex.grid
        let r = min(Float(radius), 1.5 * min(cw, ch))
        let cx0 = max(0, Int((x - r - ox) / cw)), cx1 = min(g - 1, Int((x + r - ox) / cw))
        let cy0 = max(0, Int((y - r - oy) / ch)), cy1 = min(g - 1, Int((y + r - oy) / ch))
        if cx0 > cx1 || cy0 > cy1 { return nil }
        var bestEnd = r * r, bestEdge = r * r * 0.45
        var end: (Float, Float)? = nil, edge: (Float, Float)? = nil
        for cy in cy0...cy1 {
            for cx in cx0...cx1 {
                let c = cy * g + cx
                var k = Int(start[c])
                let stop = Int(start[c + 1])
                while k < stop {
                    let i = Int(items[k]) * 4
                    k += 1
                    let x0 = seg[i], y0 = seg[i + 1], x1 = seg[i + 2], y1 = seg[i + 3]
                    let d0 = (x - x0) * (x - x0) + (y - y0) * (y - y0)
                    if d0 < bestEnd { bestEnd = d0; end = (x0, y0) }
                    let d1 = (x - x1) * (x - x1) + (y - y1) * (y - y1)
                    if d1 < bestEnd { bestEnd = d1; end = (x1, y1) }
                    let dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy
                    if l2 > 0 {
                        let t = max(0, min(1, ((x - x0) * dx + (y - y0) * dy) / l2))
                        let qx = x0 + t * dx, qy = y0 + t * dy
                        let dq = (x - qx) * (x - qx) + (y - qy) * (y - qy)
                        if dq < bestEdge { bestEdge = dq; edge = (qx, qy) }
                    }
                }
            }
        }
        if let e = end { return (CGPoint(x: CGFloat(e.0), y: CGFloat(e.1)), true) }
        if let e = edge { return (CGPoint(x: CGFloat(e.0), y: CGFloat(e.1)), false) }
        return nil
    }
}

/// Reads the vector lines of a PDF page (paths drawn with stroke or fill, forms included).
final class GeometryReader {
    private var ctm = CGAffineTransform.identity
    private var stack: [CGAffineTransform] = []
    private var cur = CGPoint.zero, subStart = CGPoint.zero
    private var pending: [Float] = []
    private(set) var out: [Float] = []
    private var depth = 0
    private var full: Bool { out.count / 4 >= SnapIndex.maxSegments }

    static func read(_ page: CGPDFPage) -> [Float] {
        let r = GeometryReader()
        guard let table = makeTable() else { return [] }
        let cs = CGPDFContentStreamCreateWithPage(page)
        r.scan(cs, table)
        CGPDFContentStreamRelease(cs)
        CGPDFOperatorTableRelease(table)
        return r.out
    }

    private func scan(_ cs: CGPDFContentStreamRef, _ table: CGPDFOperatorTableRef) {
        let info = Unmanaged.passUnretained(self).toOpaque()
        let scanner = CGPDFScannerCreate(cs, table, info)
        CGPDFScannerScan(scanner)
        CGPDFScannerRelease(scanner)
    }

    private func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
        CGPoint(x: x, y: y).applying(ctm)
    }

    private func line(_ a: CGPoint, _ b: CGPoint) {
        pending.append(Float(a.x)); pending.append(Float(a.y))
        pending.append(Float(b.x)); pending.append(Float(b.y))
    }

    private func paint() {
        if !full { out.append(contentsOf: pending) }
        pending.removeAll(keepingCapacity: true)
    }

    private static func me(_ info: UnsafeMutableRawPointer?) -> GeometryReader {
        Unmanaged<GeometryReader>.fromOpaque(info!).takeUnretainedValue()
    }

    private static func pop(_ s: CGPDFScannerRef, _ n: Int) -> [CGFloat]? {
        var v = [CGFloat](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            var x: CGPDFReal = 0
            if !CGPDFScannerPopNumber(s, &x) { return nil }
            v[i] = CGFloat(x)
        }
        return v
    }

    private static func makeTable() -> CGPDFOperatorTableRef? {
        guard let t = CGPDFOperatorTableCreate() else { return nil }
        CGPDFOperatorTableSetCallback(t, "q") { _, info in
            let r = GeometryReader.me(info)
            r.stack.append(r.ctm)
        }
        CGPDFOperatorTableSetCallback(t, "Q") { _, info in
            let r = GeometryReader.me(info)
            if let m = r.stack.popLast() { r.ctm = m }
        }
        CGPDFOperatorTableSetCallback(t, "cm") { s, info in
            let r = GeometryReader.me(info)
            guard let v = GeometryReader.pop(s, 6) else { return }
            r.ctm = CGAffineTransform(a: v[0], b: v[1], c: v[2], d: v[3], tx: v[4], ty: v[5]).concatenating(r.ctm)
        }
        CGPDFOperatorTableSetCallback(t, "m") { s, info in
            let r = GeometryReader.me(info)
            guard let v = GeometryReader.pop(s, 2) else { return }
            r.cur = r.pt(v[0], v[1])
            r.subStart = r.cur
        }
        CGPDFOperatorTableSetCallback(t, "l") { s, info in
            let r = GeometryReader.me(info)
            guard let v = GeometryReader.pop(s, 2) else { return }
            let p = r.pt(v[0], v[1])
            r.line(r.cur, p)
            r.cur = p
        }
        // curves: only their end is a snap point
        let curve: CGPDFOperatorCallback = { s, info in
            let r = GeometryReader.me(info)
            var last = CGPoint.zero
            var got = 0
            var x: CGPDFReal = 0, y: CGPDFReal = 0
            if CGPDFScannerPopNumber(s, &y) && CGPDFScannerPopNumber(s, &x) {
                last = r.pt(CGFloat(x), CGFloat(y)); got = 1
            }
            // discard the control points still on the stack
            while CGPDFScannerPopNumber(s, &x) { }
            if got == 1 {
                r.line(last, last)
                r.cur = last
            }
        }
        CGPDFOperatorTableSetCallback(t, "c", curve)
        CGPDFOperatorTableSetCallback(t, "v", curve)
        CGPDFOperatorTableSetCallback(t, "y", curve)
        CGPDFOperatorTableSetCallback(t, "h") { _, info in
            let r = GeometryReader.me(info)
            if r.cur != r.subStart { r.line(r.cur, r.subStart) }
            r.cur = r.subStart
        }
        CGPDFOperatorTableSetCallback(t, "re") { s, info in
            let r = GeometryReader.me(info)
            guard let v = GeometryReader.pop(s, 4) else { return }
            let a = r.pt(v[0], v[1]), b = r.pt(v[0] + v[2], v[1])
            let c = r.pt(v[0] + v[2], v[1] + v[3]), d = r.pt(v[0], v[1] + v[3])
            r.line(a, b); r.line(b, c); r.line(c, d); r.line(d, a)
            r.cur = a
            r.subStart = a
        }
        // painting keeps the path; "n" (clip only) throws it away
        for op in ["S", "s", "f", "F", "f*", "B", "B*", "b", "b*"] {
            CGPDFOperatorTableSetCallback(t, op) { _, info in GeometryReader.me(info).paint() }
        }
        CGPDFOperatorTableSetCallback(t, "n") { _, info in
            GeometryReader.me(info).pending.removeAll(keepingCapacity: true)
        }
        CGPDFOperatorTableSetCallback(t, "Do") { s, info in
            let r = GeometryReader.me(info)
            guard r.depth < 12, !r.full else { return }
            var namePtr: UnsafePointer<CChar>? = nil
            guard CGPDFScannerPopName(s, &namePtr), let name = namePtr else { return }
            let cs = CGPDFScannerGetContentStream(s)
            guard let obj = CGPDFContentStreamGetResource(cs, "XObject", name) else { return }
            var stream: CGPDFStreamRef? = nil
            guard CGPDFObjectGetValue(obj, .stream, &stream), let st = stream,
                  let dict = CGPDFStreamGetDictionary(st) else { return }
            var sub: UnsafePointer<CChar>? = nil
            guard CGPDFDictionaryGetName(dict, "Subtype", &sub), let subtype = sub,
                  String(cString: subtype) == "Form" else { return }
            var m = CGAffineTransform.identity
            var arr: CGPDFArrayRef? = nil
            if CGPDFDictionaryGetArray(dict, "Matrix", &arr), let a = arr, CGPDFArrayGetCount(a) == 6 {
                var v = [CGFloat](repeating: 0, count: 6)
                for i in 0..<6 {
                    var x: CGPDFReal = 0
                    if CGPDFArrayGetNumber(a, i, &x) { v[i] = CGFloat(x) }
                }
                m = CGAffineTransform(a: v[0], b: v[1], c: v[2], d: v[3], tx: v[4], ty: v[5])
            }
            var res: CGPDFDictionaryRef? = nil
            _ = CGPDFDictionaryGetDictionary(dict, "Resources", &res)
            let sub2 = CGPDFContentStreamCreateWithStream(st, res ?? dict, cs)
            let saved = r.ctm, savedStack = r.stack
            r.ctm = m.concatenating(r.ctm)
            r.depth += 1
            if let table = GeometryReader.makeTable() {
                r.scan(sub2, table)
                CGPDFOperatorTableRelease(table)
            }
            r.depth -= 1
            r.ctm = saved
            r.stack = savedStack
            CGPDFContentStreamRelease(sub2)
        }
        return t
    }
}
