import UIKit
import UniformTypeIdentifiers

/// Start screen: recent drawings and one button to open a PDF.
final class HomeViewController: UITableViewController, UIDocumentPickerDelegate {
    private var recents: [Store.Recent] = []
    private let openButton = UIButton(type: .system)

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Calque"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always
        navigationItem.rightBarButtonItem = UIBarButtonItem(image: UIImage(systemName: "slider.horizontal.3"),
                                                            style: .plain, target: self, action: #selector(openSettings))
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Settings"
        tableView.backgroundColor = Theme.background
        tableView.rowHeight = 64
        tableView.contentInset.bottom = 100

        var c = UIButton.Configuration.filled()
        c.title = "Open"
        c.image = UIImage(systemName: "plus")
        c.imagePadding = 10
        c.cornerStyle = .capsule
        c.baseBackgroundColor = Theme.accent
        c.baseForegroundColor = Theme.onAccent
        c.buttonSize = .large
        c.contentInsets = NSDirectionalEdgeInsets(top: 16, leading: 26, bottom: 16, trailing: 30)
        openButton.configuration = c
        openButton.layer.shadowColor = UIColor.black.cgColor
        openButton.layer.shadowOpacity = 0.18
        openButton.layer.shadowRadius = 10
        openButton.layer.shadowOffset = CGSize(width: 0, height: 4)
        openButton.translatesAutoresizingMaskIntoConstraints = false
        openButton.addTarget(self, action: #selector(pick), for: .touchUpInside)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
        recents = Store.recents
        tableView.reloadData()
        if openButton.superview == nil, let host = navigationController?.view {
            host.addSubview(openButton)
            NSLayoutConstraint.activate([
                openButton.centerXAnchor.constraint(equalTo: host.centerXAnchor),
                openButton.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            ])
        }
        openButton.isHidden = false
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        openButton.isHidden = true
    }

    @objc private func openSettings() {
        let nav = UINavigationController(rootViewController: SettingsViewController())
        nav.modalPresentationStyle = .formSheet
        present(nav, animated: true)
    }

    @objc private func pick() {
        // PDF drawings, and CAD drawings (DXF, DWG), which Calque converts on opening
        var types: [UTType] = [.pdf]
        for ext in ["dwg", "dxf"] {
            if let t = UTType(filenameExtension: ext) { types.append(t) }
            if let t = UTType(filenameExtension: ext, conformingTo: .data) { types.append(t) }
        }
        let p = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
        p.delegate = self
        p.allowsMultipleSelection = false
        present(p, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        open(u)
    }

    func open(_ url: URL) {
        navigationController?.popToRootViewController(animated: false)
        navigationController?.pushViewController(ViewerViewController(url: url), animated: true)
    }

    // MARK: list

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { max(1, recents.count) }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        recents.isEmpty ? nil : "Recent"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        if recents.isEmpty {
            cell.textLabel?.text = "Nothing opened yet"
            cell.detailTextLabel?.text = "Tap Open, or open a PDF, DWG or DXF from Files and choose Calque."
            cell.textLabel?.textColor = .secondaryLabel
            cell.detailTextLabel?.textColor = .secondaryLabel
            cell.detailTextLabel?.numberOfLines = 2
            cell.selectionStyle = .none
            return cell
        }
        let r = recents[indexPath.row]
        cell.imageView?.image = UIImage(systemName: "doc.text.fill")
        cell.imageView?.tintColor = Theme.accent
        cell.textLabel?.text = r.name
        cell.textLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        cell.detailTextLabel?.text = f.localizedString(for: r.time, relativeTo: Date())
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath,
                            point: CGPoint) -> UIContextMenuConfiguration? {
        guard !recents.isEmpty, UIApplication.shared.supportsMultipleScenes else { return nil }
        let r = recents[indexPath.row]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(children: [UIAction(title: "Open in New Window", image: UIImage(systemName: "rectangle.split.2x1")) { _ in
                let a = NSUserActivity(activityType: SceneDelegate.openActivity)
                a.userInfo = ["bookmark": r.bookmark]
                UIApplication.shared.requestSceneSessionActivation(nil, userActivity: a, options: nil) { _ in }
            }])
        }
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !recents.isEmpty else { return }
        let r = recents[indexPath.row]
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: r.bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
            let a = UIAlertController(title: "File not found", message: "\(r.name) was moved or deleted. Remove it from the list?", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "Keep", style: .cancel))
            a.addAction(UIAlertAction(title: "Remove", style: .destructive) { _ in
                Store.removeRecent(r.name)
                self.recents = Store.recents
                self.tableView.reloadData()
            })
            present(a, animated: true)
            return
        }
        open(url)
    }

    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard !recents.isEmpty else { return nil }
        let r = recents[indexPath.row]
        let remove = UIContextualAction(style: .destructive, title: "Remove") { _, _, done in
            let a = UIAlertController(title: "Remove from recent?",
                                      message: "\(r.name)\n\nOnly the entry in this list is removed. The PDF file itself is not deleted.",
                                      preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in done(false) })
            a.addAction(UIAlertAction(title: "Remove", style: .destructive) { _ in
                Store.removeRecent(r.name)
                self.recents = Store.recents
                self.tableView.reloadData()
                done(true)
            })
            self.present(a, animated: true)
        }
        return UISwipeActionsConfiguration(actions: [remove])
    }
}
