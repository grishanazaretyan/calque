import Foundation

/// Small persistent settings and per-document data.
enum Store {
    private static let d = UserDefaults.standard

    /// 0 = follow the iPad, 1 = light, 2 = dark.
    static var theme: Int {
        get { d.integer(forKey: "theme") }
        set { d.set(newValue, forKey: "theme") }
    }
    static var unit: Int {
        get { d.integer(forKey: "unit") }
        set { d.set(newValue, forKey: "unit") }
    }
    static var continuous: Bool {
        get { d.object(forKey: "continuous") as? Bool ?? true }
        set { d.set(newValue, forKey: "continuous") }
    }
    static var snap: Bool {
        get { d.object(forKey: "snap") as? Bool ?? true }
        set { d.set(newValue, forKey: "snap") }
    }

    static func lastPage(_ key: String) -> Int { d.integer(forKey: "page:" + key) }
    static func setLastPage(_ key: String, _ page: Int) { d.set(page, forKey: "page:" + key) }

    /// Drawing scale 1:ratio for a sheet: its own, else the document's last one, else 1:100.
    static func ratio(_ key: String, _ page: Int) -> Double {
        let own = d.double(forKey: "scale:\(key)#\(page)")
        if own > 0 { return own }
        let doc = d.double(forKey: "scale:" + key)
        return doc > 0 ? doc : 100
    }

    static func setRatio(_ key: String, _ page: Int, _ ratio: Double) {
        d.set(ratio, forKey: "scale:\(key)#\(page)")
        d.set(ratio, forKey: "scale:" + key)
    }

    // MARK: recent files

    struct Recent: Codable {
        var name: String
        var bookmark: Data
        var time: Date
    }

    static var recents: [Recent] {
        get {
            guard let data = d.data(forKey: "recents"),
                  let list = try? JSONDecoder().decode([Recent].self, from: data) else { return [] }
            return list
        }
        set { d.set(try? JSONEncoder().encode(newValue), forKey: "recents") }
    }

    static func addRecent(_ url: URL) {
        guard let bm = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else { return }
        var list = recents.filter { $0.name != url.lastPathComponent }
        list.insert(Recent(name: url.lastPathComponent, bookmark: bm, time: Date()), at: 0)
        recents = Array(list.prefix(20))
    }

    static func removeRecent(_ name: String) {
        recents = recents.filter { $0.name != name }
    }

    // MARK: files for markup and measurements

    /// Stable short hash (Swift's own hashValue changes on every launch).
    static func hash(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return String(h, radix: 16)
    }

    static func dataFile(_ kind: String, _ key: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Calque", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("\(kind)-\(hash(key)).json")
    }
}

/// Length and area formatting in the unit chosen in Settings. Values come in as millimetres.
enum Units {
    static let names = ["mm", "cm", "m", "ft-in"]

    static func len(_ mm: Double) -> String {
        switch Store.unit {
        case 1: return group(mm / 10, 1) + "\u{00A0}cm"
        case 2: return String(format: "%.3f\u{00A0}m", mm / 1000)
        case 3:
            let inch = mm / 25.4
            var ft = Int(floor(inch / 12 + 1e-9))
            var r = inch - Double(ft) * 12
            if r >= 11.95 { ft += 1; r = 0 }
            return ft > 0 ? String(format: "%d′\u{00A0}%.1f″", ft, r) : String(format: "%.1f″", r)
        default: return group(mm, 0) + "\u{00A0}mm"
        }
    }

    static func area(_ m2: Double) -> String {
        Store.unit == 3 ? String(format: "%.2f\u{00A0}ft²", m2 * 10.7639104) : String(format: "%.2f\u{00A0}m²", m2)
    }

    static var inputName: String { Store.unit == 3 ? "inches" : names[Store.unit] }

    static func toMm(_ v: Double) -> Double {
        switch Store.unit {
        case 1: return v * 10
        case 2: return v * 1000
        case 3: return v * 25.4
        default: return v
        }
    }

    private static func group(_ v: Double, _ decimals: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.groupingSeparator = "\u{2009}"
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = decimals
        f.maximumFractionDigits = decimals
        f.decimalSeparator = "."
        return f.string(from: NSNumber(value: v)) ?? String(v)
    }

    static func scaleLabel(_ r: Double) -> String {
        if r >= 1 {
            return abs(r - r.rounded()) < r * 0.002 ? "1:\(Int(r.rounded()))" : String(format: "1:%.2f", r)
        }
        let k = 1 / r
        return abs(k - k.rounded()) < k * 0.002 ? "\(Int(k.rounded())):1" : String(format: "%.2f:1", k)
    }
}
