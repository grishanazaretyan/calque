import PDFKit
import UIKit

/// One opened PDF: continuous or one-sheet view, sheet list, measuring and markup.
final class ViewerViewController: UIViewController, UIGestureRecognizerDelegate, UIPopoverPresentationControllerDelegate, PDFDocumentDelegate {

    enum Mode { case view, measure, markup }

    let url: URL
    let key: String
    private(set) var doc: PDFDocument?
    let pdfView = PDFView()
    let overlay = OverlayView()
    private var accessing = false
    private var password: String?
    private var hair: HairRenderer?

    var titles: [String?] = []
    var mode: Mode = .view

    // bars
    let topBar = Theme.bar(radius: 26)
    let titleLabel = Theme.label("", 16, .semibold)
    let subLabel = Theme.label("", 12, .regular, .secondaryLabel)
    let menuButton = Theme.iconButton("ellipsis.circle", "View options", size: 20)
    let bottomBar = Theme.bar(radius: 28)
    let sheetsButton = UIButton(type: .system)
    private var navStack = UIStackView()
    private var chromeShown = true
    private var scrollObservers: [NSKeyValueObservation] = []
    weak var scrollView: UIScrollView?

    // measuring (see Viewer+Measure.swift)
    var mPts: [CGPoint] = []
    var mPage = -1
    var mArea = false
    var finished: [Measurement] = []
    var snaps: [Int: SnapIndex] = [:]
    var snapPending = Set<Int>()
    var snapKind = 0
    let measureCard = Theme.bar(radius: 26)
    let bigLabel = Theme.label("", 28, .semibold)
    let smallLabel = Theme.label("", 13, .regular, .secondaryLabel)
    let scaleButton = UIButton(type: .system)
    let snapButton = Theme.iconButton("scope", "Snap to lines")
    let measureModeControl = UISegmentedControl(items: ["Length", "Area"])

    // markup (see Viewer+Markup.swift)
    var items: [MarkItem] = []
    var history: [MarkChange] = []
    var tool = 0
    var markColor: UInt32 = Theme.markColors[0]
    var thick = 1
    var draft: MarkItem?
    var selected: MarkItem?
    var editingItem: MarkItem?
    var inline: UITextView?
    var inlineNew: (page: Int, point: CGPoint)?
    var textMode = 0
    var dragStart = CGPoint.zero
    var selOrigin = CGPoint.zero
    var selSize = 0.0
    var anchor = CGPoint.zero
    var grabDist: CGFloat = 1
    var dragged = false
    var tapEdits = false
    var swallowPan = false
    let markCard = Theme.bar(radius: 26)
    let toolControl = UISegmentedControl(items: ["Pen", "Arrow", "Box", "Text", "Erase"])
    let thickControl = UISegmentedControl(items: ["S", "M", "L"])
    var colorButtons: [UIButton] = []
    lazy var drawPan = UIPanGestureRecognizer(target: self, action: #selector(onDrawPan(_:)))
    lazy var drawTap = UITapGestureRecognizer(target: self, action: #selector(onDrawTap(_:)))

    init(url: URL) {
        self.url = url
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        self.key = url.lastPathComponent + "|" + String(size)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    deinit {
        if HairPage.renderer === hair { HairPage.renderer = nil }
        if accessing { url.stopAccessingSecurityScopedResource() }
    }

    /// Every page of the document is a HairPage, so hairlines can be switched on and off.
    func classForPage() -> AnyClass { HairPage.self }

    // MARK: life cycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.background
        accessing = url.startAccessingSecurityScopedResource()

        pdfView.translatesAutoresizingMaskIntoConstraints = false
        pdfView.backgroundColor = Theme.background
        pdfView.displayDirection = .vertical
        pdfView.displaysPageBreaks = true
        pdfView.pageBreakMargins = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
        pdfView.pageShadowsEnabled = true
        view.addSubview(pdfView)

        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.viewer = self
        view.addSubview(overlay)

        NSLayoutConstraint.activate([
            pdfView.topAnchor.constraint(equalTo: view.topAnchor),
            pdfView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            pdfView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            pdfView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        buildTopBar()
        buildBottomBar()
        buildMeasureCard()
        buildMarkCard()

        let tap = UITapGestureRecognizer(target: self, action: #selector(onChromeTap))
        tap.delegate = self
        pdfView.addGestureRecognizer(tap)
        for g in allGestures(in: pdfView) {
            if let t = g as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { tap.require(toFail: t) }
        }

        drawPan.maximumNumberOfTouches = 1
        drawPan.delegate = self
        drawTap.delegate = self
        drawPan.isEnabled = false
        drawTap.isEnabled = false
        pdfView.addGestureRecognizer(drawPan)
        pdfView.addGestureRecognizer(drawTap)

        NotificationCenter.default.addObserver(self, selector: #selector(onPageChanged),
                                               name: .PDFViewPageChanged, object: pdfView)
        NotificationCenter.default.addObserver(self, selector: #selector(onScaleChanged),
                                               name: .PDFViewScaleChanged, object: pdfView)
        NotificationCenter.default.addObserver(self, selector: #selector(settingsChanged),
                                               name: .calqueSettingsChanged, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(saveState),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        titleLabel.text = url.deletingPathExtension().lastPathComponent
        subLabel.text = "Opening…"
        open()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        overlay.setNeedsDisplay()
        updateMeasure()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        commitInline()
        saveState()
    }

    override var prefersHomeIndicatorAutoHidden: Bool { !chromeShown }

    private func allGestures(in v: UIView) -> [UIGestureRecognizer] {
        var out = v.gestureRecognizers ?? []
        for s in v.subviews { out += allGestures(in: s) }
        return out
    }

    private func findScroll(_ v: UIView) -> UIScrollView? {
        if let s = v as? UIScrollView { return s }
        for s in v.subviews { if let f = findScroll(s) { return f } }
        return nil
    }

    // MARK: opening

    private func open() {
        guard let d = PDFDocument(url: url) else {
            fail("This file cannot be opened. It may be damaged or not a PDF.")
            return
        }
        d.delegate = self
        if d.isLocked {
            askPassword(d, wrong: false)
        } else {
            show(d)
        }
    }

    private func askPassword(_ d: PDFDocument, wrong: Bool) {
        let a = UIAlertController(title: wrong ? "Wrong password, try again" : "This PDF is password-protected",
                                  message: nil, preferredStyle: .alert)
        a.addTextField { $0.isSecureTextEntry = true }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in self.close() })
        a.addAction(UIAlertAction(title: "Open", style: .default) { _ in
            let pw = a.textFields?.first?.text ?? ""
            if d.unlock(withPassword: pw) { self.password = pw; self.show(d) }
            else { self.askPassword(d, wrong: true) }
        })
        present(a, animated: true)
    }

    private func fail(_ message: String) {
        Store.removeRecent(url.lastPathComponent)
        let a = UIAlertController(title: "Cannot open", message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in self.close() })
        DispatchQueue.main.async { self.present(a, animated: true) }
    }

    private func show(_ d: PDFDocument) {
        doc = d
        if Store.hairline { setHairline(true, refresh: false) }
        readTitles()
        pdfView.displayMode = Store.continuous ? .singlePageContinuous : .singlePage
        pdfView.document = d
        pdfView.autoScales = true
        items = MarkItem.load(Store.dataFile("markup", key))
        finished = Measurement.load(Store.dataFile("measure", key))
        Store.addRecent(url)
        let last = min(max(0, Store.lastPage(key)), d.pageCount - 1)
        navStack.isHidden = d.pageCount < 2
        DispatchQueue.main.async {
            self.applyZoomLimits()
            if last > 0, let p = d.page(at: last) { self.pdfView.go(to: p) }
            self.installScrollObservers()
            self.onPageChanged()
        }
    }

    private func applyZoomLimits() {
        pdfView.minScaleFactor = pdfView.scaleFactorForSizeToFit * 0.4
        pdfView.maxScaleFactor = max(pdfView.scaleFactorForSizeToFit * 2, 200)
    }

    private func installScrollObservers() {
        guard scrollObservers.isEmpty, let sv = findScroll(pdfView) else { return }
        scrollView = sv
        sv.decelerationRate = .normal
        scrollObservers.append(sv.observe(\.contentOffset, options: []) { [weak self] _, _ in self?.viewChanged() })
        scrollObservers.append(sv.observe(\.zoomScale, options: []) { [weak self] _, _ in self?.viewChanged() })
    }

    @objc private func onScaleChanged() { viewChanged() }

    /// The drawing moved or zoomed: redraw marks and refresh the live measurement.
    func viewChanged() {
        // redrawing the full-screen layer on every scroll step makes panning stutter
        if mode != .view || !items.isEmpty { overlay.setNeedsDisplay() }
        if mode == .measure { keepMeasureInsets() }
        if mode == .measure {
            ensureSnap()
            updateMeasure()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        overlay.setNeedsDisplay()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { _ in
            self.applyZoomLimits()
            self.overlay.setNeedsDisplay()
        }
    }

    @objc private func settingsChanged() {
        if doc != nil && Store.hairline != (HairPage.renderer != nil) { setHairline(Store.hairline, refresh: true) }
        updateMeasure()
        overlay.setNeedsDisplay()
    }

    @objc func saveState() {
        guard doc != nil else { return }
        Measurement.save(finished, to: Store.dataFile("measure", key))
        MarkItem.save(items, to: Store.dataFile("markup", key))
    }

    @objc private func close() {
        commitInline()
        if HairPage.renderer === hair { HairPage.renderer = nil }
        if let nav = navigationController, nav.viewControllers.count > 1 { nav.popViewController(animated: true) }
        else { dismiss(animated: true) }
    }

    // MARK: sheets

    var pageCount: Int { doc?.pageCount ?? 0 }

    var currentIndex: Int {
        guard let d = doc, let p = pdfView.currentPage else { return 0 }
        return d.index(for: p)
    }

    func sheetName(_ i: Int) -> String {
        if i < titles.count, let t = titles[i] { return t }
        return "Sheet \(i + 1)"
    }

    /// The deepest bookmark pointing at a sheet names it, else its page label.
    private func readTitles() {
        guard let d = doc else { return }
        var t = [String?](repeating: nil, count: d.pageCount)
        for i in 0..<d.pageCount {
            if let l = d.page(at: i)?.label?.trimmingCharacters(in: .whitespaces), !l.isEmpty, l != String(i + 1) {
                t[i] = l
            }
        }
        var depth = [Int](repeating: 0, count: d.pageCount)
        func walk(_ o: PDFOutline, _ level: Int) {
            guard level <= 12 else { return }
            for k in 0..<o.numberOfChildren {
                guard let c = o.child(at: k) else { continue }
                var page = c.destination?.page
                if page == nil, let a = c.action as? PDFActionGoTo { page = a.destination.page }
                if let p = page, let label = c.label?.trimmingCharacters(in: .whitespacesAndNewlines), !label.isEmpty {
                    let i = d.index(for: p)
                    if i >= 0 && i < t.count && level > depth[i] {
                        t[i] = label.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
                        depth[i] = level
                    }
                }
                walk(c, level + 1)
            }
        }
        if let root = d.outlineRoot { walk(root, 1) }
        titles = t
    }

    @objc func onPageChanged() {
        guard let d = doc, d.pageCount > 0 else { return }
        let i = currentIndex
        sheetsButton.configuration?.title = "\(i + 1) / \(d.pageCount)"
        if d.pageCount > 1 {
            subLabel.text = titles[i].map { "\(i + 1)  ·  \($0)" } ?? "Sheet \(i + 1) of \(d.pageCount)"
        } else if let p = d.page(at: 0) {
            subLabel.text = titles[0] ?? Theme.paper(p.bounds(for: .cropBox).size)
        }
        Store.setLastPage(key, i)
        if mode == .measure { updateMeasure() }
    }

    func goTo(_ index: Int) {
        guard let d = doc, let p = d.page(at: max(0, min(d.pageCount - 1, index))) else { return }
        pdfView.go(to: p)
    }

    @objc private func showSheets() {
        guard let d = doc else { return }
        let list = SheetListController(viewer: self, count: d.pageCount)
        let nav = UINavigationController(rootViewController: list)
        nav.modalPresentationStyle = .popover
        nav.preferredContentSize = CGSize(width: 420, height: min(640, 70 + 60 * d.pageCount))
        nav.popoverPresentationController?.sourceView = sheetsButton
        nav.popoverPresentationController?.delegate = self
        present(nav, animated: true)
    }

    func paperSize(_ i: Int) -> String {
        guard let p = doc?.page(at: i) else { return "" }
        var s = p.bounds(for: .cropBox).size
        if p.rotation % 180 != 0 { s = CGSize(width: s.height, height: s.width) }
        return Theme.paper(s)
    }

    // MARK: bars

    private func buildTopBar() {
        view.addSubview(topBar)
        let back = Theme.iconButton("chevron.left", "Back", size: 18)
        back.addTarget(self, action: #selector(close), for: .touchUpInside)
        titleLabel.lineBreakMode = .byTruncatingMiddle
        subLabel.lineBreakMode = .byTruncatingTail
        let names = UIStackView(arrangedSubviews: [titleLabel, subLabel])
        names.axis = .vertical
        names.spacing = 1
        menuButton.showsMenuAsPrimaryAction = true
        menuButton.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            done(self?.viewMenuItems() ?? [])
        }])
        let row = UIStackView(arrangedSubviews: [back, names, menuButton])
        row.alignment = .center
        row.spacing = 6
        row.translatesAutoresizingMaskIntoConstraints = false
        topBar.contentView.addSubview(row)
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            topBar.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 56),
            row.leadingAnchor.constraint(equalTo: topBar.contentView.leadingAnchor, constant: 6),
            row.trailingAnchor.constraint(equalTo: topBar.contentView.trailingAnchor, constant: -6),
            row.centerYAnchor.constraint(equalTo: topBar.contentView.centerYAnchor),
            back.widthAnchor.constraint(equalToConstant: 44),
            menuButton.widthAnchor.constraint(equalToConstant: 44),
        ])
        fixWidth(topBar, 760)
    }

    private func viewMenuItems() -> [UIMenuElement] {
        var out: [UIMenuElement] = []
        if pageCount > 1 {
            let cont = UIAction(title: "Continuous scroll", image: UIImage(systemName: "rectangle.grid.1x2"),
                                state: pdfView.displayMode == .singlePageContinuous ? .on : .off) { [weak self] _ in
                self?.setContinuous(true)
            }
            let single = UIAction(title: "One sheet at a time", image: UIImage(systemName: "doc"),
                                  state: pdfView.displayMode == .singlePage ? .on : .off) { [weak self] _ in
                self?.setContinuous(false)
            }
            out.append(UIMenu(options: .displayInline, children: [cont, single]))
        }
        out.append(UIAction(title: "Hairlines", image: UIImage(systemName: "line.diagonal"),
                            state: Store.hairline ? .on : .off) { [weak self] _ in
            Store.hairline.toggle()
            self?.setHairline(Store.hairline, refresh: true)
        })
        out.append(UIAction(title: "Settings", image: UIImage(systemName: "slider.horizontal.3")) { [weak self] _ in
            self?.openSettings()
        })
        return out
    }

    private func setContinuous(_ on: Bool) {
        Store.continuous = on
        let page = pdfView.currentPage
        pdfView.displayMode = on ? .singlePageContinuous : .singlePage
        pdfView.autoScales = true
        if let p = page { pdfView.go(to: p) }
        DispatchQueue.main.async { self.applyZoomLimits(); self.overlay.setNeedsDisplay() }
    }

    private func openSettings() {
        let nav = UINavigationController(rootViewController: SettingsViewController())
        nav.modalPresentationStyle = .formSheet
        present(nav, animated: true)
    }

    private func buildBottomBar() {
        view.addSubview(bottomBar)
        let prev = Theme.iconButton("chevron.left", "Previous sheet")
        prev.addAction(UIAction { [weak self] _ in
            guard let s = self else { return }
            s.goTo(s.currentIndex - 1)
        }, for: .touchUpInside)
        let next = Theme.iconButton("chevron.right", "Next sheet")
        next.addAction(UIAction { [weak self] _ in
            guard let s = self else { return }
            s.goTo(s.currentIndex + 1)
        }, for: .touchUpInside)
        var c = UIButton.Configuration.filled()
        c.image = UIImage(systemName: "list.bullet")
        c.imagePadding = 8
        c.cornerStyle = .capsule
        c.baseBackgroundColor = Theme.chip
        c.baseForegroundColor = .label
        c.title = "1 / 1"
        sheetsButton.configuration = c
        sheetsButton.accessibilityLabel = "Sheets"
        sheetsButton.addTarget(self, action: #selector(showSheets), for: .touchUpInside)
        let divider = UIView()
        divider.backgroundColor = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false
        navStack = UIStackView(arrangedSubviews: [prev, sheetsButton, next, divider])
        navStack.alignment = .center
        navStack.spacing = 4
        let ruler = Theme.iconButton("ruler", "Measure")
        ruler.addAction(UIAction { [weak self] _ in self?.setMode(.measure) }, for: .touchUpInside)
        let pen = Theme.iconButton("pencil.tip", "Markup")
        pen.addAction(UIAction { [weak self] _ in self?.setMode(.markup) }, for: .touchUpInside)
        let row = UIStackView(arrangedSubviews: [navStack, ruler, pen])
        row.alignment = .center
        row.spacing = 6
        row.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.contentView.addSubview(row)
        NSLayoutConstraint.activate([
            bottomBar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            bottomBar.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: 58),
            row.leadingAnchor.constraint(equalTo: bottomBar.contentView.leadingAnchor, constant: 10),
            row.trailingAnchor.constraint(equalTo: bottomBar.contentView.trailingAnchor, constant: -10),
            row.centerYAnchor.constraint(equalTo: bottomBar.contentView.centerYAnchor),
            prev.widthAnchor.constraint(equalToConstant: 44),
            next.widthAnchor.constraint(equalToConstant: 44),
            ruler.widthAnchor.constraint(equalToConstant: 48),
            pen.widthAnchor.constraint(equalToConstant: 48),
            divider.widthAnchor.constraint(equalToConstant: 1),
            divider.heightAnchor.constraint(equalToConstant: 24),
        ])
    }

    @objc private func onChromeTap() {
        guard mode == .view else { return }
        chromeShown.toggle()
        UIView.animate(withDuration: 0.18) {
            self.topBar.alpha = self.chromeShown ? 1 : 0
            self.bottomBar.alpha = self.chromeShown ? 1 : 0
        }
        setNeedsUpdateOfHomeIndicatorAutoHidden()
    }

    /// A bar or card as wide as `width`, but never wider than the screen minus a margin.
    func fixWidth(_ v: UIView, _ width: CGFloat) {
        let want = v.widthAnchor.constraint(equalToConstant: width)
        want.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            want,
            v.widthAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.widthAnchor, constant: -24),
        ])
    }

    // MARK: hairlines

    /// Draws every line at hairline width through PDFium, like Hairline in ArchiCAD.
    func setHairline(_ on: Bool, refresh: Bool) {
        if on {
            if hair == nil { hair = HairRenderer(url: url, password: password) }
            if hair == nil {
                Store.hairline = false
                let a = UIAlertController(title: "Hairlines", message: "This file cannot be drawn with hairlines.", preferredStyle: .alert)
                a.addAction(UIAlertAction(title: "OK", style: .default))
                present(a, animated: true)
            }
            HairPage.renderer = hair
        } else {
            HairPage.renderer = nil
        }
        guard refresh, let d = doc else { return }
        // PDFKit keeps the sheets it has drawn; give it the document again so it redraws them
        let dest = pdfView.currentDestination
        let s = pdfView.scaleFactor
        pdfView.document = nil
        pdfView.document = d
        pdfView.scaleFactor = s
        if let dest = dest { pdfView.go(to: dest) }
        DispatchQueue.main.async {
            self.applyZoomLimits()
            self.overlay.setNeedsDisplay()
        }
    }

    // MARK: measuring room

    /// While measuring, any corner of a sheet can be brought to the cross in the middle.
    func keepMeasureInsets() {
        guard let sv = scrollView else { return }
        let want = mode == .measure
            ? UIEdgeInsets(top: view.bounds.height / 2, left: view.bounds.width / 2,
                           bottom: view.bounds.height / 2, right: view.bounds.width / 2)
            : .zero
        if sv.contentInset != want { sv.contentInset = want }
    }

    func setMode(_ m: Mode) {
        guard doc != nil else { return }
        commitInline()
        if mode == .markup && m != .markup { endMarkup() }
        mode = m
        chromeShown = true
        topBar.alpha = 1
        bottomBar.alpha = m == .view ? 1 : 0
        bottomBar.isHidden = m != .view
        measureCard.isHidden = m != .measure
        markCard.isHidden = m != .markup
        if m == .markup { beginMarkup() }
        if m == .measure { ensureSnap(); updateMeasure() }
        keepMeasureInsets()
        overlay.setNeedsDisplay()
    }

    // MARK: gestures

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        // our drawing gestures must not run together with the PDF view's own
        if g === drawPan || g === drawTap || other === drawPan || other === drawTap { return false }
        return true
    }

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        if g === drawPan || g === drawTap { return mode == .markup }
        return true
    }

    func adaptivePresentationStyle(for controller: UIPresentationController) -> UIModalPresentationStyle { .none }
}

/// Draws measurements and markup above the PDF; touches go through to the PDF view.
final class OverlayView: UIView {
    weak var viewer: ViewerViewController?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func draw(_ rect: CGRect) {
        guard let v = viewer, let c = UIGraphicsGetCurrentContext() else { return }
        v.drawMarkup(in: c)
        v.drawMeasure(in: c)
    }
}

/// The sheet list: number, title and paper size; tap to jump.
final class SheetListController: UITableViewController, UISearchResultsUpdating {
    private weak var viewer: ViewerViewController?
    private let all: [Int]
    private var shown: [Int]

    init(viewer: ViewerViewController, count: Int) {
        self.viewer = viewer
        all = Array(0..<count)
        shown = all
        super.init(style: .plain)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Sheets"
        tableView.rowHeight = 60
        if all.count > 12 {
            let s = UISearchController(searchResultsController: nil)
            s.searchResultsUpdater = self
            s.obscuresBackgroundDuringPresentation = false
            s.searchBar.placeholder = "Find a sheet"
            navigationItem.searchController = s
            navigationItem.hidesSearchBarWhenScrolling = false
        }
        if let v = viewer, v.currentIndex < shown.count {
            DispatchQueue.main.async {
                self.tableView.scrollToRow(at: IndexPath(row: v.currentIndex, section: 0), at: .middle, animated: false)
            }
        }
    }

    func updateSearchResults(for searchController: UISearchController) {
        let q = searchController.searchBar.text?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""
        shown = q.isEmpty ? all : all.filter {
            (viewer?.sheetName($0).lowercased().contains(q) ?? false) || String($0 + 1) == q
        }
        tableView.reloadData()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { shown.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "sheet")
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: "sheet")
        let i = shown[indexPath.row]
        let current = i == viewer?.currentIndex
        cell.textLabel?.text = "\(i + 1)   " + (viewer?.sheetName(i) ?? "")
        cell.textLabel?.font = .systemFont(ofSize: 16, weight: current ? .semibold : .regular)
        cell.textLabel?.textColor = current ? Theme.accent : .label
        cell.detailTextLabel?.text = viewer?.paperSize(i)
        cell.detailTextLabel?.textColor = .secondaryLabel
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let i = shown[indexPath.row]
        dismiss(animated: true) { self.viewer?.goTo(i) }
    }
}
