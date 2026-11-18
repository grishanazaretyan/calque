import PDFKit
import UIKit

extension ViewerViewController: UITextViewDelegate {
    static let strokePt: [Double] = [1.5, 3, 6]
    static let textPt: [Double] = [12, 17, 26]
    static let toolPen = 0, toolArrow = 1, toolBox = 2, toolText = 3, toolErase = 4

    // MARK: card

    func buildMarkCard() {
        markCard.isHidden = true
        view.addSubview(markCard)
        fixWidth(markCard, 560)
        toolControl.selectedSegmentIndex = 0
        toolControl.addAction(UIAction { [weak self] _ in
            guard let s = self else { return }
            s.commitInline()
            s.tool = s.toolControl.selectedSegmentIndex
            if s.tool != Self.toolText { s.select(nil) }
            s.updateMarkCard()
        }, for: .valueChanged)

        var colorViews: [UIView] = []
        for (i, hex) in Theme.markColors.enumerated() {
            let b = UIButton(type: .custom)
            b.backgroundColor = UIColor(hex: hex)
            b.layer.cornerRadius = 15
            b.accessibilityLabel = "Colour \(i + 1)"
            b.translatesAutoresizingMaskIntoConstraints = false
            b.widthAnchor.constraint(equalToConstant: 30).isActive = true
            b.heightAnchor.constraint(equalToConstant: 30).isActive = true
            b.addAction(UIAction { [weak self] _ in
                guard let s = self else { return }
                s.markColor = hex
                if let tv = s.inline { tv.textColor = UIColor(hex: hex) }
                if let e = s.editingItem { e.color = hex }
                s.setSelectedColor(hex)
                s.updateMarkCard()
            }, for: .touchUpInside)
            colorButtons.append(b)
            colorViews.append(b)
        }
        thickControl.selectedSegmentIndex = thick
        thickControl.addAction(UIAction { [weak self] _ in
            guard let s = self else { return }
            s.thick = s.thickControl.selectedSegmentIndex
            if s.tool == Self.toolText { s.setSelectedSize(s.thick) }
            s.updateMarkCard()
        }, for: .valueChanged)
        let colors = UIStackView(arrangedSubviews: colorViews)
        colors.spacing = 10
        let rowB = UIStackView(arrangedSubviews: [colors, UIView(), thickControl])
        rowB.alignment = .center
        rowB.spacing = 10

        let hint = Theme.label("One finger draws · two fingers move\nText: drag to move, corner to resize", 12, .regular, .secondaryLabel)
        hint.numberOfLines = 2
        let undo = Theme.pill("Undo", filled: false)
        undo.addAction(UIAction { [weak self] _ in
            self?.commitInline()
            self?.undoMarkup()
        }, for: .touchUpInside)
        let done = Theme.pill("Done", filled: true)
        done.addAction(UIAction { [weak self] _ in self?.setMode(.view) }, for: .touchUpInside)
        let rowC = UIStackView(arrangedSubviews: [hint, undo, done])
        rowC.alignment = .center
        rowC.spacing = 8

        let col = UIStackView(arrangedSubviews: [toolControl, rowB, rowC])
        col.axis = .vertical
        col.spacing = 12
        col.translatesAutoresizingMaskIntoConstraints = false
        markCard.contentView.addSubview(col)
        NSLayoutConstraint.activate([
            markCard.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            markCard.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            col.topAnchor.constraint(equalTo: markCard.contentView.topAnchor, constant: 14),
            col.bottomAnchor.constraint(equalTo: markCard.contentView.bottomAnchor, constant: -14),
            col.leadingAnchor.constraint(equalTo: markCard.contentView.leadingAnchor, constant: 16),
            col.trailingAnchor.constraint(equalTo: markCard.contentView.trailingAnchor, constant: -16),
            thickControl.widthAnchor.constraint(equalToConstant: 150),
        ])
        updateMarkCard()
    }

    func updateMarkCard() {
        for (i, b) in colorButtons.enumerated() {
            let on = Theme.markColors[i] == markColor
            b.layer.borderWidth = on ? 3 : 1
            b.layer.borderColor = (on ? UIColor.label : UIColor.separator).cgColor
        }
        let text = tool == Self.toolText
        for i in 0..<3 {
            thickControl.setImage(Self.thicknessImage(i, text: text), forSegmentAt: i)
        }
        thickControl.isEnabled = tool != Self.toolErase
        overlay.setNeedsDisplay()
    }

    /// A line of the given width, or a letter "A" of the given size for the Text tool.
    static func thicknessImage(_ i: Int, text: Bool) -> UIImage {
        let size = CGSize(width: 34, height: 22)
        let img = UIGraphicsImageRenderer(size: size).image { _ in
            if text {
                let font = UIFont.systemFont(ofSize: [10, 14, 19][i], weight: .bold)
                let s = ("A" as NSString).size(withAttributes: [.font: font])
                ("A" as NSString).draw(at: CGPoint(x: (size.width - s.width) / 2, y: (size.height - s.height) / 2),
                                       withAttributes: [.font: font, .foregroundColor: UIColor.black])
            } else {
                let p = UIBezierPath()
                p.move(to: CGPoint(x: 6, y: size.height / 2))
                p.addLine(to: CGPoint(x: size.width - 6, y: size.height / 2))
                p.lineWidth = CGFloat(strokePt[i]) * 1.2
                p.lineCapStyle = .round
                UIColor.black.setStroke()
                p.stroke()
            }
        }
        return img.withRenderingMode(.alwaysTemplate)
    }

    func beginMarkup() {
        drawPan.isEnabled = true
        drawTap.isEnabled = true
        // one finger draws; the PDF view keeps two-finger pan and pinch zoom
        scrollView?.panGestureRecognizer.minimumNumberOfTouches = 2
        updateMarkCard()
    }

    func endMarkup() {
        drawPan.isEnabled = false
        drawTap.isEnabled = false
        scrollView?.panGestureRecognizer.minimumNumberOfTouches = 1
        draft = nil
        select(nil)
        saveState()
    }

    // MARK: coordinates

    var scale: CGFloat { max(pdfView.scaleFactor, 0.0001) }

    func pageIndex(at p: CGPoint) -> Int? {
        guard let d = doc, let page = pdfView.page(for: p, nearest: true) else { return nil }
        return d.index(for: page)
    }

    func toPage(_ p: CGPoint, _ page: Int) -> CGPoint {
        guard let pg = doc?.page(at: page) else { return p }
        return pdfView.convert(p, to: pg)
    }

    func toView(_ p: CGPoint, _ page: Int) -> CGPoint {
        guard let pg = doc?.page(at: page) else { return p }
        return pdfView.convert(p, from: pg)
    }

    // MARK: undo

    func record(_ c: MarkChange) {
        history.append(c)
        MarkItem.save(items, to: Store.dataFile("markup", key))
        overlay.setNeedsDisplay()
    }

    func undoMarkup() {
        guard let c = history.popLast() else { return }
        switch c {
        case .added(let it):
            items.removeAll { $0 === it }
        case .removed(let it, let index):
            items.insert(it, at: min(index, items.count))
        case .edited(let it, let text, let width, let color, let origin):
            it.text = text
            it.width = width
            it.color = color
            if !it.pts.isEmpty { it.pts[0] = origin }
        }
        if let s = selected, !items.contains(where: { $0 === s }) { select(nil) }
        MarkItem.save(items, to: Store.dataFile("markup", key))
        overlay.setNeedsDisplay()
    }

    func remove(_ it: MarkItem) {
        guard let i = items.firstIndex(where: { $0 === it }) else { return }
        if it === selected { select(nil) }
        items.remove(at: i)
        record(.removed(it, i))
    }

    // MARK: touches

    @objc func onDrawPan(_ g: UIPanGestureRecognizer) {
        let p = g.location(in: pdfView)
        switch g.state {
        case .began:
            let start = CGPoint(x: p.x - g.translation(in: pdfView).x, y: p.y - g.translation(in: pdfView).y)
            // a touch that finishes typing a note does only that
            swallowPan = inline != nil
            if swallowPan { commitInline(); return }
            down(start)
            move(p)
        case .changed:
            if !swallowPan { move(p) }
        case .ended:
            if !swallowPan { up(p) }
            swallowPan = false
        default:
            if !swallowPan { cancel() }
            swallowPan = false
        }
    }

    @objc func onDrawTap(_ g: UITapGestureRecognizer) {
        if inline != nil { commitInline(); return }
        let p = g.location(in: pdfView)
        down(p)
        up(p)
    }

    private func down(_ p: CGPoint) {
        dragStart = p
        dragged = false
        switch tool {
        case Self.toolText:
            textDown(p)
        case Self.toolErase:
            erase(at: p)
        default:
            guard let pi = pageIndex(at: p) else { return }
            let type = tool == Self.toolPen ? MarkItem.pen : tool == Self.toolArrow ? MarkItem.arrow : MarkItem.box
            let d = MarkItem(type: type, page: pi, color: markColor, width: Self.strokePt[thick] / Double(scale))
            let q = toPage(p, pi)
            d.pts = type == MarkItem.pen ? [q] : [q, q]
            draft = d
        }
    }

    private func move(_ p: CGPoint) {
        if hypot(p.x - dragStart.x, p.y - dragStart.y) > 6 { dragged = true }
        switch tool {
        case Self.toolText:
            textMove(p)
        case Self.toolErase:
            erase(at: p)
        default:
            guard let d = draft else { return }
            let q = toPage(p, d.page)
            if d.type == MarkItem.pen { d.pts.append(q) } else { d.pts[1] = q }
            overlay.setNeedsDisplay()
        }
    }

    private func up(_ p: CGPoint) {
        switch tool {
        case Self.toolText:
            textUp(p)
        case Self.toolErase:
            break
        default:
            if let d = draft, dragged {
                items.append(d)
                record(.added(d))
            }
            draft = nil
            overlay.setNeedsDisplay()
        }
    }

    private func cancel() {
        draft = nil
        if tool == Self.toolText { textCancel() }
        overlay.setNeedsDisplay()
    }

    // MARK: erase

    private func hits(_ it: MarkItem, _ p: CGPoint, _ tol: CGFloat) -> Bool {
        if it.type == MarkItem.text { return textRect(it).insetBy(dx: -tol, dy: -tol).contains(p) }
        let s = it.pts.map { toView($0, it.page) }
        if it.type == MarkItem.box, s.count >= 2 {
            let r = CGRect(x: min(s[0].x, s[1].x), y: min(s[0].y, s[1].y), width: abs(s[1].x - s[0].x), height: abs(s[1].y - s[0].y))
            let corners = [CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.minY), CGPoint(x: r.maxX, y: r.maxY), CGPoint(x: r.minX, y: r.maxY)]
            for i in 0..<4 where Self.segDist(p, corners[i], corners[(i + 1) % 4]) < tol { return true }
            return false
        }
        if s.count == 1 { return hypot(p.x - s[0].x, p.y - s[0].y) < tol }
        for i in 1..<max(1, s.count) where Self.segDist(p, s[i - 1], s[i]) < tol { return true }
        return false
    }

    static func segDist(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = b.x - a.x, dy = b.y - a.y, l2 = dx * dx + dy * dy
        let t = l2 == 0 ? 0 : max(0, min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2))
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    private func erase(at p: CGPoint) {
        for i in stride(from: items.count - 1, through: 0, by: -1) where hits(items[i], p, 14) {
            remove(items[i])
            return
        }
    }

    // MARK: text notes: select, move, resize (like a text layer)

    func font(_ it: MarkItem) -> UIFont { .systemFont(ofSize: max(1, CGFloat(it.width) * scale)) }

    /// Screen rectangle of a note's text. A note's point is its top-left corner.
    func textRect(_ it: MarkItem) -> CGRect {
        guard let o = it.pts.first else { return .zero }
        let tl = toView(o, it.page)
        let size = ((it.text ?? "") as NSString).size(withAttributes: [.font: font(it)])
        return CGRect(x: tl.x, y: tl.y, width: max(size.width, CGFloat(it.width) * scale * 0.6), height: size.height)
    }

    func select(_ it: MarkItem?) {
        selected = it
        overlay.setNeedsDisplay()
    }

    private func selectionBox() -> CGRect { textRect(selected!).insetBy(dx: -6, dy: -6) }

    private func editSelected(_ change: () -> Void) {
        guard let s = selected, let o = s.pts.first else { return }
        let t = s.text, w = s.width, c = s.color
        change()
        record(.edited(s, text: t, width: w, color: c, origin: o))
    }

    func setSelectedColor(_ hex: UInt32) {
        guard let s = selected, s.color != hex else { return }
        editSelected { s.color = hex }
    }

    func setSelectedSize(_ i: Int) {
        guard let s = selected else { return }
        let w = Self.textPt[i] / Double(scale)
        editSelected { s.width = w }
    }

    private func textAt(_ p: CGPoint) -> MarkItem? {
        for it in items.reversed() where it.type == MarkItem.text && hits(it, p, 10) { return it }
        return nil
    }

    private func textDown(_ p: CGPoint) {
        textMode = 0
        tapEdits = false
        let grab: CGFloat = 26
        if let s = selected {
            let b = selectionBox()
            if hypot(p.x - b.maxX, p.y - b.minY) < grab { textMode = 4 }
            else if hypot(p.x - b.maxX, p.y - b.maxY) < grab { textMode = 2 }
            else if b.contains(p) { textMode = 1; tapEdits = true }
            _ = s
        }
        if textMode == 0 {
            if let hit = textAt(p) { select(hit); textMode = 1 } else { textMode = 3 }
        }
        if (textMode == 1 || textMode == 2), let s = selected, let o = s.pts.first {
            selOrigin = o
            selSize = s.width
            let r = textRect(s)
            anchor = r.origin
            grabDist = max(8, hypot(p.x - anchor.x, p.y - anchor.y))
        }
    }

    private func textMove(_ p: CGPoint) {
        guard dragged, let s = selected else { return }
        if textMode == 1 {
            let a = toPage(dragStart, s.page), b = toPage(p, s.page)
            s.pts[0] = CGPoint(x: selOrigin.x + b.x - a.x, y: selOrigin.y + b.y - a.y)
        } else if textMode == 2 {
            let f = Double(hypot(p.x - anchor.x, p.y - anchor.y) / grabDist)
            s.width = max(4 / Double(scale), min(400 / Double(scale), selSize * f))
        }
        overlay.setNeedsDisplay()
    }

    private func textUp(_ p: CGPoint) {
        switch textMode {
        case 4:
            if let s = selected, !dragged { remove(s) }
        case 1, 2:
            guard let s = selected else { break }
            if dragged {
                record(.edited(s, text: s.text, width: selSize, color: s.color, origin: selOrigin))
            } else if tapEdits {
                openInline(for: s)
            }
        case 3:
            if selected != nil {
                select(nil)
            } else if let pi = pageIndex(at: p) {
                openInlineNew(page: pi, at: p)
            }
        default:
            break
        }
        textMode = 0
    }

    private func textCancel() {
        if let s = selected, dragged, textMode == 1 || textMode == 2 {
            s.pts[0] = selOrigin
            s.width = selSize
        }
        textMode = 0
    }

    // MARK: text typed in place

    private func makeInline(text: String, color: UInt32, fontSize: CGFloat, at p: CGPoint) {
        let tv = UITextView()
        tv.text = text
        tv.textColor = UIColor(hex: color)
        tv.font = .systemFont(ofSize: max(14, min(56, fontSize)))
        tv.backgroundColor = Theme.selection.withAlphaComponent(0.08)
        tv.layer.borderColor = Theme.selection.cgColor
        tv.layer.borderWidth = 1.5
        tv.layer.cornerRadius = 4
        tv.isScrollEnabled = false
        tv.textContainerInset = UIEdgeInsets(top: 2, left: 2, bottom: 2, right: 2)
        tv.delegate = self
        view.addSubview(tv)
        let w = min(view.bounds.width - 24, max(180, tv.sizeThatFits(CGSize(width: 2000, height: 2000)).width + 24))
        let x = max(12, min(p.x - 4, view.bounds.width - w - 12))
        tv.frame = CGRect(x: x, y: max(view.safeAreaInsets.top, p.y - 4), width: w,
                          height: tv.sizeThatFits(CGSize(width: w, height: 2000)).height)
        inline = tv
        tv.becomeFirstResponder()
    }

    func textViewDidChange(_ tv: UITextView) {
        let w = min(view.bounds.width - tv.frame.minX - 12,
                    max(180, tv.sizeThatFits(CGSize(width: 2000, height: 2000)).width + 24))
        tv.frame.size = CGSize(width: w, height: tv.sizeThatFits(CGSize(width: w, height: 2000)).height)
    }

    private func openInlineNew(page: Int, at p: CGPoint) {
        commitInline()
        inlineNew = (page, toPage(p, page))
        editingItem = nil
        makeInline(text: "", color: markColor, fontSize: CGFloat(Self.textPt[thick]), at: p)
    }

    private func openInline(for it: MarkItem) {
        commitInline()
        inlineNew = nil
        editingItem = it
        let r = textRect(it)
        makeInline(text: it.text ?? "", color: it.color, fontSize: CGFloat(it.width) * scale, at: r.origin)
        overlay.setNeedsDisplay()
    }

    /// Finishes the note being typed: adds it, saves the new wording, or deletes it if emptied.
    func commitInline() {
        guard let tv = inline else { return }
        inline = nil
        let t = (tv.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        tv.resignFirstResponder()
        tv.removeFromSuperview()
        let it = editingItem
        editingItem = nil
        if let n = inlineNew {
            inlineNew = nil
            if !t.isEmpty {
                let item = MarkItem(type: MarkItem.text, page: n.page, color: markColor,
                                    width: Self.textPt[thick] / Double(scale), pts: [n.point], text: t)
                items.append(item)
                record(.added(item))
            }
        } else if let e = it {
            if t.isEmpty {
                remove(e)
            } else if t != e.text, let o = e.pts.first {
                let old = e.text
                e.text = t
                record(.edited(e, text: old, width: e.width, color: e.color, origin: o))
            }
        }
        overlay.setNeedsDisplay()
    }

    // MARK: drawing

    func drawMarkup(in c: CGContext) {
        for it in items where it !== editingItem { draw(it) }
        if let d = draft { draw(d) }
        drawSelection(c)
    }

    private func draw(_ it: MarkItem) {
        guard doc?.page(at: it.page) != nil else { return }
        let color = UIColor(hex: it.color)
        if it.type == MarkItem.text {
            guard let o = it.pts.first, let text = it.text else { return }
            (text as NSString).draw(at: toView(o, it.page), withAttributes: [.font: font(it), .foregroundColor: color])
            return
        }
        let s = it.pts.map { toView($0, it.page) }
        guard s.count >= 2 else { return }
        let w = max(1, CGFloat(it.width) * scale)
        let path = UIBezierPath()
        path.lineWidth = w
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        if it.type == MarkItem.box {
            path.append(UIBezierPath(rect: CGRect(x: min(s[0].x, s[1].x), y: min(s[0].y, s[1].y),
                                                  width: abs(s[1].x - s[0].x), height: abs(s[1].y - s[0].y))))
            path.lineWidth = w
        } else if it.type == MarkItem.arrow {
            let a = s[0], b = s[1]
            path.move(to: a)
            path.addLine(to: b)
            let ang = atan2(b.y - a.y, b.x - a.x)
            let h = min(w * 5, hypot(b.x - a.x, b.y - a.y) * 0.5)
            path.move(to: b)
            path.addLine(to: CGPoint(x: b.x - h * cos(ang - 0.45), y: b.y - h * sin(ang - 0.45)))
            path.move(to: b)
            path.addLine(to: CGPoint(x: b.x - h * cos(ang + 0.45), y: b.y - h * sin(ang + 0.45)))
        } else {
            path.move(to: s[0])
            for p in s.dropFirst() { path.addLine(to: p) }
        }
        color.setStroke()
        path.stroke()
    }

    private func drawSelection(_ c: CGContext) {
        guard mode == .markup, tool == Self.toolText, let s = selected, s !== editingItem else { return }
        let b = textRect(s).insetBy(dx: -6, dy: -6)
        let frame = UIBezierPath(rect: b)
        frame.lineWidth = 1.5
        frame.setLineDash([5, 4], count: 2, phase: 0)
        Theme.selection.setStroke()
        frame.stroke()
        let r: CGFloat = 9, a = r * 0.45
        // resize handle, bottom-right
        let rh = UIBezierPath(ovalIn: CGRect(x: b.maxX - r, y: b.maxY - r, width: 2 * r, height: 2 * r))
        UIColor.white.setFill()
        rh.fill()
        rh.lineWidth = 2
        rh.stroke()
        let diag = UIBezierPath()
        diag.move(to: CGPoint(x: b.maxX - a, y: b.maxY - a))
        diag.addLine(to: CGPoint(x: b.maxX + a, y: b.maxY + a))
        diag.lineWidth = 2
        diag.stroke()
        // delete handle, top-right
        let dh = UIBezierPath(ovalIn: CGRect(x: b.maxX - r, y: b.minY - r, width: 2 * r, height: 2 * r))
        UIColor(hex: 0xE53935).setFill()
        dh.fill()
        let x = UIBezierPath()
        x.move(to: CGPoint(x: b.maxX - a, y: b.minY - a)); x.addLine(to: CGPoint(x: b.maxX + a, y: b.minY + a))
        x.move(to: CGPoint(x: b.maxX - a, y: b.minY + a)); x.addLine(to: CGPoint(x: b.maxX + a, y: b.minY - a))
        x.lineWidth = 2
        UIColor.white.setStroke()
        x.stroke()
    }
}
