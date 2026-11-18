import UIKit

final class SettingsViewController: UITableViewController {
    private struct Section {
        let title: String
        let footer: String?
        let options: [String]
        let get: () -> Int
        let set: (Int) -> Void
    }

    private lazy var sections: [Section] = [
        Section(title: "Theme", footer: nil, options: ["System", "Light", "Dark"],
                get: { Store.theme }, set: { Store.theme = $0; Theme.applyToAllWindows() }),
        Section(title: "Measurement units", footer: "Lengths, areas and calibration input.",
                options: Units.names, get: { Store.unit }, set: { Store.unit = $0 }),
        Section(title: "Snap to lines", footer: "While measuring, the cross jumps to line ends and edges of the drawing.",
                options: ["Off", "On"], get: { Store.snap ? 1 : 0 }, set: { Store.snap = $0 == 1 }),
        Section(title: "Default view", footer: nil, options: ["Continuous", "One sheet"],
                get: { Store.continuous ? 0 : 1 }, set: { Store.continuous = $0 == 0 }),
    ]

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Settings"
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true)
        })
    }

    override func numberOfSections(in tableView: UITableView) -> Int { sections.count + 1 }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { 1 }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section < sections.count ? sections[section].title : nil
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section < sections.count { return sections[section].footer }
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        return "Calque \(v) · PDF engine: Apple PDFKit"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell()
        cell.selectionStyle = .none
        guard indexPath.section < sections.count else {
            cell.textLabel?.text = "Calque for iPad"
            cell.textLabel?.textColor = .secondaryLabel
            return cell
        }
        let s = sections[indexPath.section]
        let seg = UISegmentedControl(items: s.options)
        seg.selectedSegmentIndex = s.get()
        seg.addAction(UIAction { _ in s.set(seg.selectedSegmentIndex) }, for: .valueChanged)
        seg.translatesAutoresizingMaskIntoConstraints = false
        cell.contentView.addSubview(seg)
        NSLayoutConstraint.activate([
            seg.leadingAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.leadingAnchor),
            seg.trailingAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.trailingAnchor),
            seg.topAnchor.constraint(equalTo: cell.contentView.topAnchor, constant: 10),
            seg.bottomAnchor.constraint(equalTo: cell.contentView.bottomAnchor, constant: -10),
        ])
        return cell
    }
}
