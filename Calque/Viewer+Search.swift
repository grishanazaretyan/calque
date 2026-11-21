import PDFKit
import UIKit

/// Text search: a bar under the top bar, matches highlighted, next / previous.
extension ViewerViewController: UITextFieldDelegate {

    func buildSearch() {
        searchCard.isHidden = true
        searchCard.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(searchCard)
        searchField.placeholder = "Search text"
        searchField.returnKeyType = .search
        searchField.clearButtonMode = .whileEditing
        searchField.autocorrectionType = .no
        searchField.delegate = self
        searchField.font = .systemFont(ofSize: 16)
        let prev = Theme.iconButton("chevron.up", "Previous match", size: 18)
        prev.addAction(UIAction { [weak self] _ in self?.stepHit(-1) }, for: .touchUpInside)
        let next = Theme.iconButton("chevron.down", "Next match", size: 18)
        next.addAction(UIAction { [weak self] _ in self?.stepHit(1) }, for: .touchUpInside)
        let close = Theme.iconButton("xmark", "Close search", size: 18)
        close.addAction(UIAction { [weak self] _ in self?.closeSearch() }, for: .touchUpInside)
        searchCount.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [searchField, searchCount, prev, next, close])
        row.alignment = .center
        row.spacing = 6
        row.translatesAutoresizingMaskIntoConstraints = false
        searchCard.contentView.addSubview(row)
        NSLayoutConstraint.activate([
            searchCard.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 8),
            searchCard.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            searchCard.heightAnchor.constraint(equalToConstant: 52),
            row.leadingAnchor.constraint(equalTo: searchCard.contentView.leadingAnchor, constant: 16),
            row.trailingAnchor.constraint(equalTo: searchCard.contentView.trailingAnchor, constant: -6),
            row.centerYAnchor.constraint(equalTo: searchCard.contentView.centerYAnchor),
            prev.widthAnchor.constraint(equalToConstant: 40),
            next.widthAnchor.constraint(equalToConstant: 40),
            close.widthAnchor.constraint(equalToConstant: 40),
        ])
        fixWidth(searchCard, 560)
        NotificationCenter.default.addObserver(self, selector: #selector(searchFound(_:)),
                                               name: .PDFDocumentDidFindMatch, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(searchEnded(_:)),
                                               name: .PDFDocumentDidEndFind, object: nil)
    }

    @objc func openSearch() {
        guard doc != nil else { return }
        searchCard.isHidden = false
        searchField.becomeFirstResponder()
    }

    func closeSearch() {
        doc?.cancelFindString()
        searchField.resignFirstResponder()
        searchCard.isHidden = true
        hits = []
        searchCount.text = ""
        pdfView.highlightedSelections = nil
        pdfView.clearSelection()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        runSearch()
        return true
    }

    private func runSearch() {
        let q = (searchField.text ?? "").trimmingCharacters(in: .whitespaces)
        guard let d = doc, !q.isEmpty else { return }
        searchField.resignFirstResponder()
        d.cancelFindString()
        hits = []
        found = []
        searchCount.text = "…"
        // finds on a background thread; the matches arrive when it ends
        pendingQuery = q
        d.beginFindString(q, withOptions: [.caseInsensitive, .diacriticInsensitive])
    }

    @objc private func searchFound(_ n: Notification) {
        guard let d = n.object as? PDFDocument, d === doc,
              let sel = n.userInfo?["PDFDocumentFoundSelection"] as? PDFSelection else { return }
        DispatchQueue.main.async { if self.pendingQuery != nil { self.found.append(sel) } }
    }

    @objc private func searchEnded(_ n: Notification) {
        guard let d = n.object as? PDFDocument, d === doc else { return }
        DispatchQueue.main.async {
            guard self.pendingQuery != nil else { return }
            self.pendingQuery = nil
            let list = self.found
            self.found = []
            self.showHits(list)
        }
    }

    private func showHits(_ found: [PDFSelection]) {
        hits = found
        for h in hits { h.color = UIColor.systemYellow.withAlphaComponent(0.55) }
        pdfView.highlightedSelections = hits
        if hits.isEmpty {
            searchCount.text = "0"
            tell("No matches")
            return
        }
        hitIndex = 0
        goToHit()
    }

    private func stepHit(_ d: Int) {
        guard !hits.isEmpty else { runSearch(); return }
        hitIndex = ((hitIndex + d) % hits.count + hits.count) % hits.count
        goToHit()
    }

    private func goToHit() {
        let h = hits[hitIndex]
        searchCount.text = "\(hitIndex + 1)/\(hits.count)"
        pdfView.setCurrentSelection(h, animate: false)
        // zoom in so the match is readable, then bring it to the middle
        if let page = h.pages.first {
            let box = h.bounds(for: page)
            let want = pdfView.bounds.width * 0.3 / max(box.width, box.height * 6, 1)
            if pdfView.scaleFactor < want { pdfView.scaleFactor = min(want, pdfView.maxScaleFactor) }
            pdfView.go(to: box, on: page)
        }
        overlay.setNeedsDisplay()
    }
}
