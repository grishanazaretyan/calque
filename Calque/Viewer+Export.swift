import PDFKit
import UIKit

/// Open in another app, Share, Save to Files and Print — the same as on Android.
extension ViewerViewController: UIDocumentInteractionControllerDelegate {

    func exportMenu() -> UIMenu {
        UIMenu(options: .displayInline, children: [
            UIAction(title: "Open in…", image: UIImage(systemName: "arrow.up.forward.app")) { [weak self] _ in
                self?.plain { $0.withCopy { $0.openIn($1) } }
            },
            UIAction(title: "Share", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in
                self?.plain { $0.withCopy { $0.share($1) } }
            },
            UIAction(title: "Save to Files", image: UIImage(systemName: "folder")) { [weak self] _ in
                self?.plain { $0.withCopy { $0.saveToFiles($1) } }
            },
            UIAction(title: "Print", image: UIImage(systemName: "printer")) { [weak self] _ in
                self?.plain { $0.withCopy { $0.printFile($1) } }
            },
            UIAction(title: "Export with markup…", image: UIImage(systemName: "pencil.and.outline")) { [weak self] _ in
                self?.exportWithMarkup()
            },
        ])
    }

    private func plain(_ then: (ViewerViewController) -> Void) {
        exportSource = nil
        exportName = nil
        then(self)
    }

    // MARK: export with markup

    /// A copy of the PDF with pen, text and measurements burnt into the sheets, then
    /// Share / Save to Files / Print / Open in.
    func exportWithMarkup() {
        guard let d = doc else { return }
        if items.isEmpty && finished.isEmpty {
            tell("Nothing drawn or measured yet")
            return
        }
        let a = UIAlertController(title: "Export with markup", message: nil, preferredStyle: .actionSheet)
        let actions: [(String, (ViewerViewController, URL) -> Void)] = [
            ("Share", { $0.share($1) }), ("Save to Files", { $0.saveToFiles($1) }),
            ("Print", { $0.printFile($1) }), ("Open in…", { $0.openIn($1) }),
        ]
        for (title, run) in actions {
            a.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                guard let self = self, let file = self.writeMarked(d) else { self?.tell("Could not export this file"); return }
                self.exportSource = file
                self.exportName = (self.url.deletingPathExtension().lastPathComponent) + " (markup)"
                self.withCopy(run)
            })
        }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let p = a.popoverPresentationController { p.sourceView = menuButton; p.sourceRect = menuButton.bounds }
        present(a, animated: true)
    }

    private func writeMarked(_ shown: PDFDocument) -> URL? {
        guard let copy = PDFDocument(url: docURL) else { return nil }
        if copy.isLocked, let pw = passwordForExport { copy.unlock(withPassword: pw) }
        for it in items {
            guard let page = copy.page(at: it.page) else { continue }
            let color = UIColor(hex: it.color)
            let w = CGFloat(max(0.3, it.width))
            if it.type == MarkItem.text {
                guard let o = it.pts.first, let text = it.text, !text.isEmpty else { continue }
                let font = UIFont.systemFont(ofSize: CGFloat(it.width))
                let size = (text as NSString).boundingRect(with: CGSize(width: 10000, height: 10000), options: [.usesLineFragmentOrigin],
                                                           attributes: [.font: font], context: nil).size
                let r = CGRect(x: o.x, y: o.y - size.height, width: size.width + font.pointSize * 0.3, height: size.height)
                let an = PDFAnnotation(bounds: r, forType: .freeText, withProperties: nil)
                an.contents = text
                an.font = font
                an.fontColor = color
                an.color = .clear
                an.border = { let b = PDFBorder(); b.lineWidth = 0; return b }()
                page.addAnnotation(an)
                continue
            }
            guard it.pts.count >= 2 else { continue }
            var paths: [UIBezierPath] = []
            let a = it.pts[0], b = it.pts[1]
            if it.type == MarkItem.box {
                paths.append(UIBezierPath(rect: CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(b.x - a.x), height: abs(b.y - a.y))))
            } else if it.type == MarkItem.arrow {
                let p = UIBezierPath()
                p.move(to: a); p.addLine(to: b)
                let ang = atan2(b.y - a.y, b.x - a.x), h = min(w * 5, hypot(b.x - a.x, b.y - a.y) * 0.5)
                p.move(to: CGPoint(x: b.x - h * cos(ang - 0.45), y: b.y - h * sin(ang - 0.45)))
                p.addLine(to: b)
                p.addLine(to: CGPoint(x: b.x - h * cos(ang + 0.45), y: b.y - h * sin(ang + 0.45)))
                paths.append(p)
            } else {
                let p = UIBezierPath()
                p.move(to: it.pts[0])
                for q in it.pts.dropFirst() { p.addLine(to: q) }
                paths.append(p)
            }
            addInk(page, paths, color, w)
        }
        // finished measurements, sized to the sheet so they print legibly
        for m in finished {
            guard let page = copy.page(at: m.page), m.pts.count >= 2 else { continue }
            let box = page.bounds(for: .cropBox)
            let big = max(box.width, box.height)
            let lw = max(0.5, big / 1400), ts = max(4, big / 180)
            let p = UIBezierPath()
            p.move(to: m.pts[0])
            for q in m.pts.dropFirst() { p.addLine(to: q) }
            if m.area { p.close() }
            addInk(page, [p], Theme.mark, lw)
            let segs = m.area ? m.pts.count : m.pts.count - 1
            var total = 0.0
            for i in 0..<max(0, segs) {
                let u = m.pts[i], v = m.pts[(i + 1) % m.pts.count]
                let d = Self.dist(u, v)
                total += d
                let text = Units.len(toMm(d, m.page))
                if d > Double(ts) * (0.6 * Double(text.count) + 1.5) {
                    addTag(page, text, CGPoint(x: (u.x + v.x) / 2, y: (u.y + v.y) / 2), ts)
                }
            }
            if m.area {
                let (area, _) = polygon(m.pts, m.page)
                let cx = m.pts.map { $0.x }.reduce(0, +) / CGFloat(m.pts.count), cy = m.pts.map { $0.y }.reduce(0, +) / CGFloat(m.pts.count)
                addTag(page, Units.area(area), CGPoint(x: cx, y: cy), ts * 1.15)
            } else if m.pts.count > 2, let last = m.pts.last {
                addTag(page, "Σ " + Units.len(toMm(total, m.page)), CGPoint(x: last.x, y: last.y + ts * 2.2), ts * 1.15)
            }
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("marked-\(UUID().uuidString).pdf")
        let ok = copy.write(to: out, withOptions: [.burnInAnnotationsOption: true])
        return ok ? out : nil
    }

    private func addInk(_ page: PDFPage, _ paths: [UIBezierPath], _ color: UIColor, _ width: CGFloat) {
        var r = CGRect.null
        for p in paths { r = r.union(p.bounds) }
        r = r.insetBy(dx: -width * 3, dy: -width * 3)
        let an = PDFAnnotation(bounds: r, forType: .ink, withProperties: nil)
        for p in paths {
            // ink paths are relative to the annotation's origin
            let q = p.copy() as! UIBezierPath
            q.apply(CGAffineTransform(translationX: -r.minX, y: -r.minY))
            an.add(q)
        }
        an.color = color
        let b = PDFBorder()
        b.lineWidth = width
        an.border = b
        page.addAnnotation(an)
    }

    private func addTag(_ page: PDFPage, _ text: String, _ at: CGPoint, _ size: CGFloat) {
        let font = UIFont.systemFont(ofSize: size, weight: .semibold)
        let w = (text as NSString).size(withAttributes: [.font: font]).width
        let r = CGRect(x: at.x - w / 2 - size * 0.45, y: at.y - size * 0.85, width: w + size * 0.9, height: size * 1.7)
        let an = PDFAnnotation(bounds: r, forType: .freeText, withProperties: nil)
        an.contents = text
        an.font = font
        an.fontColor = .white
        an.color = UIColor(white: 0.1, alpha: 0.9)
        an.alignment = .center
        an.border = { let b = PDFBorder(); b.lineWidth = 0; return b }()
        page.addAnnotation(an)
    }

    private var pdfName: String {
        var n = url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespaces)
        if let o = exportName { n = o }
        if n.isEmpty { n = "drawing" }
        n = n.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return n + ".pdf"
    }

    /// Other apps get a plain copy in our temporary folder, so they never depend on our
    /// access to the original (iCloud, another app's folder, a USB drive).
    private func withCopy(_ then: @escaping (ViewerViewController, URL) -> Void) {
        let src = exportSource ?? docURL
        let name = pdfName
        DispatchQueue.global(qos: .userInitiated).async {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let dst = dir.appendingPathComponent(name)
            var ok = false
            let scoped = src.startAccessingSecurityScopedResource()
            var err: NSError?
            NSFileCoordinator().coordinate(readingItemAt: src, options: .withoutChanges, error: &err) { real in
                ok = (try? FileManager.default.copyItem(at: real, to: dst)) != nil
            }
            if scoped { src.stopAccessingSecurityScopedResource() }
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if ok { then(self, dst) } else { self.tell("Could not read this file") }
            }
        }
    }

    func tell(_ msg: String) {
        let a = UIAlertController(title: nil, message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default))
        present(a, animated: true)
    }

    private func anchor(_ vc: UIViewController) {
        if let p = vc.popoverPresentationController {
            p.sourceView = menuButton
            p.sourceRect = menuButton.bounds
        }
    }

    private func openIn(_ file: URL) {
        let dic = UIDocumentInteractionController(url: file)
        dic.uti = "com.adobe.pdf"
        dic.delegate = self
        docController = dic
        if !dic.presentOpenInMenu(from: menuButton.bounds, in: menuButton, animated: true) {
            tell("No other app can open PDFs")
        }
    }

    private func share(_ file: URL) {
        let vc = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        anchor(vc)
        present(vc, animated: true)
    }

    private func saveToFiles(_ file: URL) {
        let vc = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
        present(vc, animated: true)
    }

    private func printFile(_ file: URL) {
        guard UIPrintInteractionController.canPrint(file) else { tell("This file cannot be printed"); return }
        let pc = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = file.lastPathComponent
        pc.printInfo = info
        pc.printingItem = file
        pc.present(from: menuButton.bounds, in: menuButton, animated: true)
    }

    func documentInteractionControllerDidDismissOpenInMenu(_ controller: UIDocumentInteractionController) {
        docController = nil
    }
}
