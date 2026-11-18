import PDFKit
import UIKit

extension ViewerViewController {
    static let mmPerPt = 25.4 / 72
    static let presets: [Double] = [1, 5, 10, 20, 25, 50, 75, 100, 200, 250, 500, 1000]

    // MARK: card

    func buildMeasureCard() {
        measureCard.isHidden = true
        view.addSubview(measureCard)
        bigLabel.adjustsFontSizeToFitWidth = true
        bigLabel.minimumScaleFactor = 0.6
        smallLabel.numberOfLines = 2

        var sc = UIButton.Configuration.bordered()
        sc.cornerStyle = .capsule
        sc.baseForegroundColor = Theme.accent
        sc.title = "1:100"
        scaleButton.configuration = sc
        scaleButton.showsMenuAsPrimaryAction = true
        scaleButton.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            done(self?.scaleMenu() ?? [])
        }])
        let close = Theme.iconButton("xmark", "Stop measuring")
        close.addAction(UIAction { [weak self] _ in self?.setMode(.view) }, for: .touchUpInside)
        let texts = UIStackView(arrangedSubviews: [bigLabel, smallLabel])
        texts.axis = .vertical
        let rowA = UIStackView(arrangedSubviews: [texts, scaleButton, close])
        rowA.alignment = .center
        rowA.spacing = 8

        measureModeControl.selectedSegmentIndex = 0
        measureModeControl.addAction(UIAction { [weak self] _ in
            guard let s = self else { return }
            s.mArea = s.measureModeControl.selectedSegmentIndex == 1
            s.updateMeasure()
            s.overlay.setNeedsDisplay()
        }, for: .valueChanged)
        snapButton.addAction(UIAction { [weak self] _ in
            Store.snap.toggle()
            self?.ensureSnap()
            self?.updateMeasure()
            self?.overlay.setNeedsDisplay()
        }, for: .touchUpInside)
        let undo = Theme.pill("Undo", filled: false)
        undo.addAction(UIAction { [weak self] _ in self?.undoPoint() }, for: .touchUpInside)
        let clear = Theme.pill("Clear", filled: false)
        clear.addAction(UIAction { [weak self] _ in self?.clearPoints() }, for: .touchUpInside)
        let spacer = UIView()
        let rowB = UIStackView(arrangedSubviews: [measureModeControl, spacer, snapButton, undo, clear])
        rowB.alignment = .center
        rowB.spacing = 8

        let finish = Theme.pill("Finish", filled: false)
        finish.addAction(UIAction { [weak self] _ in self?.finishMeasure() }, for: .touchUpInside)
        let add = Theme.pill("Add point", filled: true)
        add.addAction(UIAction { [weak self] _ in self?.addPoint() }, for: .touchUpInside)
        let rowC = UIStackView(arrangedSubviews: [finish, add])
        rowC.distribution = .fillProportionally
        rowC.spacing = 10

        let col = UIStackView(arrangedSubviews: [rowA, rowB, rowC])
        col.axis = .vertical
        col.spacing = 12
        col.translatesAutoresizingMaskIntoConstraints = false
        measureCard.contentView.addSubview(col)
        let wide = measureCard.widthAnchor.constraint(equalTo: view.safeAreaLayoutGuide.widthAnchor, constant: -24)
        wide.priority = .defaultHigh
        NSLayoutConstraint.activate([
            measureCard.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            measureCard.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            measureCard.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
            wide,
            col.topAnchor.constraint(equalTo: measureCard.contentView.topAnchor, constant: 14),
            col.bottomAnchor.constraint(equalTo: measureCard.contentView.bottomAnchor, constant: -14),
            col.leadingAnchor.constraint(equalTo: measureCard.contentView.leadingAnchor, constant: 16),
            col.trailingAnchor.constraint(equalTo: measureCard.contentView.trailingAnchor, constant: -16),
            add.widthAnchor.constraint(equalTo: finish.widthAnchor, multiplier: 1.6),
            snapButton.widthAnchor.constraint(equalToConstant: 40),
        ])
    }

    // MARK: geometry

    /// The aiming cross: middle of the drawing area left between the bars.
    var crossPoint: CGPoint {
        let topY = topBar.frame.maxY
        let bottomY = measureCard.isHidden ? view.bounds.maxY : measureCard.frame.minY
        return CGPoint(x: view.bounds.midX, y: (topY + bottomY) / 2)
    }

    /// The sheet being measured: the one of the first point, else the one under the cross.
    var measurePage: Int {
        if !mPts.isEmpty && mPage >= 0 { return mPage }
        guard let d = doc, let p = pdfView.page(for: crossPoint, nearest: true) else { return 0 }
        return d.index(for: p)
    }

    func ratio(_ page: Int) -> Double { Store.ratio(key, page) }

    /// The cross position in sheet points, snapped to the drawing's own lines when close.
    func cursorPoint() -> CGPoint? {
        let pi = measurePage
        guard let page = doc?.page(at: pi) else { return nil }
        let raw = pdfView.convert(crossPoint, to: page)
        snapKind = 0
        if Store.snap, let ix = snaps[pi],
           let hit = ix.query(raw, radius: 16 / max(pdfView.scaleFactor, 0.0001)) {
            snapKind = hit.1 ? 1 : 2
            return hit.0
        }
        return raw
    }

    /// Reads the vector lines of the sheet being measured, once, in the background.
    func ensureSnap() {
        guard mode == .measure, Store.snap, let d = doc else { return }
        let pi = measurePage
        guard snaps[pi] == nil, !snapPending.contains(pi), let page = d.page(at: pi), let ref = page.pageRef else { return }
        snapPending.insert(pi)
        let box = page.bounds(for: .mediaBox)
        DispatchQueue.global(qos: .userInitiated).async {
            let segs = GeometryReader.read(ref)
            let ix = SnapIndex(segments: segs, bounds: box)
            DispatchQueue.main.async {
                if self.snaps.count >= 3, let k = self.snaps.keys.first { self.snaps.removeValue(forKey: k) }
                self.snaps[pi] = ix
                self.snapPending.remove(pi)
                self.updateMeasure()
                self.overlay.setNeedsDisplay()
            }
        }
    }

    static func dist(_ a: CGPoint, _ b: CGPoint) -> Double { Double(hypot(a.x - b.x, a.y - b.y)) }

    func toMm(_ pt: Double, _ page: Int) -> Double { pt * Self.mmPerPt * ratio(page) }

    /// {area m², perimeter mm} of a closed polygon in sheet points.
    func polygon(_ p: [CGPoint], _ page: Int) -> (Double, Double) {
        guard p.count >= 3 else { return (0, 0) }
        var a = 0.0, per = 0.0
        for i in 0..<p.count {
            let u = p[i], v = p[(i + 1) % p.count]
            a += Double(u.x * v.y - v.x * u.y)
            per += Self.dist(u, v)
        }
        let mm = Self.mmPerPt * ratio(page)
        return (abs(a) / 2 * mm * mm / 1e6, per * mm)
    }

    // MARK: actions

    func addPoint() {
        guard let c = cursorPoint() else { return }
        if mPts.isEmpty { mPage = measurePage }
        mPts.append(c)
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    func undoPoint() {
        if !mPts.isEmpty {
            mPts.removeLast()
        } else if let m = finished.popLast() {
            // reopen the last finished measurement
            mPts = m.pts
            mPage = m.page
            mArea = m.area
            measureModeControl.selectedSegmentIndex = m.area ? 1 : 0
        }
        if mPts.isEmpty { mPage = -1 }
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    func clearPoints() {
        mPts.removeAll()
        finished.removeAll()
        mPage = -1
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    func finishMeasure() {
        guard mPts.count >= (mArea ? 3 : 2) else {
            let a = UIAlertController(title: nil, message: mArea ? "An area needs at least 3 corners" : "A line needs at least 2 points", preferredStyle: .alert)
            present(a, animated: true)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { a.dismiss(animated: true) }
            return
        }
        finished.append(Measurement(page: mPage, area: mArea, pts: mPts))
        mPts.removeAll()
        mPage = -1
        saveState()
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    func updateMeasure() {
        guard mode == .measure, doc != nil else { return }
        scaleButton.configuration?.title = Units.scaleLabel(ratio(measurePage))
        snapButton.configuration?.baseForegroundColor = Store.snap ? Theme.accent : .tertiaryLabel
        let n = mPts.count, page = measurePage
        let cur = cursorPoint()
        if n == 0 {
            bigLabel.text = mArea ? "Area" : "Length"
            smallLabel.text = finished.isEmpty
                ? "Move the drawing until the cross is on the first point, then tap Add point"
                : "\(finished.count) kept · aim at the next start point and tap Add point"
            return
        }
        if !mArea {
            var placed = 0.0
            for i in 1..<max(1, n) { placed += Self.dist(mPts[i - 1], mPts[i]) }
            let live = cur.map { Self.dist(mPts[n - 1], $0) } ?? 0
            if n == 1 {
                bigLabel.text = Units.len(toMm(live, page))
                smallLabel.text = "Move the cross to the next point, then Add point"
            } else {
                bigLabel.text = Units.len(toMm(placed, page))
                smallLabel.text = "to the cross: +" + Units.len(toMm(live, page)) + "  ·  Finish ends this line"
            }
        } else if n < 2 {
            bigLabel.text = "Area"
            smallLabel.text = "Mark the corners one by one — at least 3"
        } else {
            let (a, per) = polygon(mPts + (cur.map { [$0] } ?? []), page)
            bigLabel.text = Units.area(a)
            smallLabel.text = "perimeter " + Units.len(per) + "  ·  Finish closes the shape"
        }
    }

    // MARK: scale

    private func scaleMenu() -> [UIMenuElement] {
        var presets: [UIMenuElement] = []
        let current = ratio(measurePage)
        for r in Self.presets {
            presets.append(UIAction(title: Units.scaleLabel(r), state: abs(r - current) < 1e-6 ? .on : .off) { [weak self] _ in
                self?.applyRatio(r)
            })
        }
        return [
            UIAction(title: "Calibrate from a known dimension…", image: UIImage(systemName: "ruler")) { [weak self] _ in
                self?.calibrate()
            },
            UIAction(title: "Other scale…", image: UIImage(systemName: "number")) { [weak self] _ in
                self?.askScale()
            },
            UIMenu(options: .displayInline, children: presets),
        ]
    }

    func applyRatio(_ r: Double) {
        guard r > 0, r.isFinite else { return }
        Store.setRatio(key, measurePage, r)
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    private func askScale() {
        let a = UIAlertController(title: "Scale 1 : ?", message: nil, preferredStyle: .alert)
        a.addTextField { $0.keyboardType = .decimalPad; $0.placeholder = "for 1:150 type 150" }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.applyRatio(Self.number(a.textFields?.first?.text))
        })
        present(a, animated: true)
    }

    private func calibrate() {
        var p = mPts, page = mPage
        if p.count < 2, let last = finished.last { p = last.pts; page = last.page }
        guard p.count >= 2 else {
            let a = UIAlertController(title: "Calibrate",
                                      message: "First mark the two ends of a dimension whose real length you know (for example a dimension line that says 3600), then open this menu again.",
                                      preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "OK", style: .default))
            present(a, animated: true)
            return
        }
        let seg = Self.dist(p[p.count - 2], p[p.count - 1])
        let a = UIAlertController(title: "Real length of the last segment, " + Units.inputName, message: nil, preferredStyle: .alert)
        a.addTextField { $0.keyboardType = .decimalPad; $0.placeholder = "real length in " + Units.inputName }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.addAction(UIAlertAction(title: "Set scale", style: .default) { [weak self] _ in
            let mm = Units.toMm(Self.number(a.textFields?.first?.text))
            guard let s = self, mm > 0, seg > 0 else { return }
            let r = mm / (seg * Self.mmPerPt)
            Store.setRatio(s.key, page >= 0 ? page : s.measurePage, r)
            s.updateMeasure()
            s.overlay.setNeedsDisplay()
        })
        present(a, animated: true)
    }

    static func number(_ s: String?) -> Double {
        Double((s ?? "").trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")) ?? 0
    }

    // MARK: drawing

    private func screen(_ p: CGPoint, _ page: Int) -> CGPoint {
        guard let pg = doc?.page(at: page) else { return .zero }
        return pdfView.convert(p, from: pg)
    }

    func tag(_ c: CGContext, _ text: String, _ at: CGPoint) {
        let font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        let size = (text as NSString).size(withAttributes: [.font: font])
        let r = CGRect(x: at.x - size.width / 2 - 6, y: at.y - 10, width: size.width + 12, height: 20)
        UIColor(white: 0.1, alpha: 0.9).setFill()
        UIBezierPath(roundedRect: r, cornerRadius: 5).fill()
        (text as NSString).draw(at: CGPoint(x: r.minX + 6, y: r.midY - size.height / 2),
                                withAttributes: [.font: font, .foregroundColor: UIColor.white])
    }

    private func dot(_ c: CGContext, _ p: CGPoint, _ r: CGFloat) {
        let path = UIBezierPath(ovalIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
        UIColor.white.setFill()
        path.fill()
        Theme.mark.setStroke()
        path.lineWidth = 2
        path.stroke()
    }

    private func polyline(_ pts: [CGPoint], close: Bool) -> UIBezierPath {
        let path = UIBezierPath()
        for (i, p) in pts.enumerated() { if i == 0 { path.move(to: p) } else { path.addLine(to: p) } }
        if close { path.close() }
        path.lineWidth = 2.5
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        return path
    }

    func drawMeasure(in c: CGContext) {
        guard mode == .measure, doc != nil else { return }
        // kept measurements
        for m in finished {
            let s = m.pts.map { screen($0, m.page) }
            let path = polyline(s, close: m.area)
            if m.area { Theme.mark.withAlphaComponent(0.2).setFill(); path.fill() }
            Theme.mark.setStroke()
            path.stroke()
            let segs = m.area ? s.count : s.count - 1
            var total = 0.0
            for i in 0..<max(0, segs) {
                let u = m.pts[i], v = m.pts[(i + 1) % m.pts.count]
                total += Self.dist(u, v)
                let text = Units.len(toMm(Self.dist(u, v), m.page))
                let a = s[i], b = s[(i + 1) % s.count]
                if hypot(a.x - b.x, a.y - b.y) > CGFloat(text.count) * 8 + 28 {
                    tag(c, text, CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2))
                }
            }
            for p in s { dot(c, p, 3.5) }
            if m.area {
                let (a, _) = polygon(m.pts, m.page)
                let cx = s.map { $0.x }.reduce(0, +) / CGFloat(s.count), cy = s.map { $0.y }.reduce(0, +) / CGFloat(s.count)
                tag(c, Units.area(a), CGPoint(x: cx, y: cy))
            } else if s.count > 2, let last = s.last {
                tag(c, "Σ " + Units.len(toMm(total, m.page)), CGPoint(x: last.x, y: last.y - 22))
            }
        }
        // the measurement in progress
        let cross = crossPoint
        var aim = cross
        let pi = measurePage
        if let cur = cursorPoint() { aim = screen(cur, pi) }
        if !mPts.isEmpty {
            let s = mPts.map { screen($0, pi) }
            if mArea && s.count >= 2 {
                let fill = polyline(s + [aim], close: true)
                Theme.mark.withAlphaComponent(0.2).setFill()
                fill.fill()
            }
            Theme.mark.setStroke()
            polyline(s, close: false).stroke()
            let live = UIBezierPath()
            live.move(to: s[s.count - 1])
            live.addLine(to: aim)
            if mArea && s.count >= 2 { live.move(to: aim); live.addLine(to: s[0]) }
            live.lineWidth = 2
            live.setLineDash([6, 5], count: 2, phase: 0)
            live.stroke()
            for i in 1..<max(1, s.count) {
                let text = Units.len(toMm(Self.dist(mPts[i - 1], mPts[i]), pi))
                if hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y) > CGFloat(text.count) * 8 + 28 {
                    tag(c, text, CGPoint(x: (s[i].x + s[i - 1].x) / 2, y: (s[i].y + s[i - 1].y) / 2))
                }
            }
            for p in s { dot(c, p, 4.5) }
        }
        if snapKind != 0 {
            // snap mark: a square on an end point, a diamond on an edge
            let h: CGFloat = 7
            c.saveGState()
            c.translateBy(x: aim.x, y: aim.y)
            if snapKind == 2 { c.rotate(by: .pi / 4) }
            let r = CGRect(x: -h, y: -h, width: 2 * h, height: 2 * h)
            UIColor.white.withAlphaComponent(0.8).setStroke()
            c.setLineWidth(3.5)
            c.stroke(r)
            Theme.mark.setStroke()
            c.setLineWidth(2)
            c.stroke(r)
            c.restoreGState()
        }
        // the aiming cross, fixed in the middle of the drawing area
        let g: CGFloat = 5, len: CGFloat = 26
        for (color, width) in [(UIColor.white.withAlphaComponent(0.8), CGFloat(3.5)), (Theme.cross, CGFloat(1.5))] {
            color.setStroke()
            let p = UIBezierPath()
            p.move(to: CGPoint(x: cross.x - len, y: cross.y)); p.addLine(to: CGPoint(x: cross.x - g, y: cross.y))
            p.move(to: CGPoint(x: cross.x + g, y: cross.y)); p.addLine(to: CGPoint(x: cross.x + len, y: cross.y))
            p.move(to: CGPoint(x: cross.x, y: cross.y - len)); p.addLine(to: CGPoint(x: cross.x, y: cross.y - g))
            p.move(to: CGPoint(x: cross.x, y: cross.y + g)); p.addLine(to: CGPoint(x: cross.x, y: cross.y + len))
            p.lineWidth = width
            p.stroke()
        }
    }
}
