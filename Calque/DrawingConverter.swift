import Foundation

/// CAD drawings (DXF, and DWG through LibreDWG) turned into a PDF that the viewer shows:
/// the model as the first page, then every layout, each at a known drawing scale.
enum DrawingConverter {
    enum Kind { case pdf, dxf, dwg, other }

    struct Result {
        let pdf: URL
        let ratios: [Double]
        let names: [String?]
    }

    /// What a file is, from its first bytes (the extension is often missing or wrong).
    static func kind(of url: URL) -> Kind {
        var head = Data()
        coordinated(url) { real in
            if let h = try? FileHandle(forReadingFrom: real) {
                head = (try? h.read(upToCount: 1024)) ?? Data()
                try? h.close()
            }
        }
        let text = String(decoding: head, as: UTF8.self)
        let latin = String(data: head, encoding: .isoLatin1) ?? ""
        if latin.contains("%PDF") { return .pdf }
        if latin.hasPrefix("AC10") || latin.hasPrefix("AC1.") { return .dwg }
        if latin.hasPrefix("AutoCAD Binary DXF") { return .dxf }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}")))
        if (t.hasPrefix("0") || t.hasPrefix("999")) && latin.contains("SECTION") { return .dxf }
        let ext = url.pathExtension.lowercased()
        return ext == "dwg" ? .dwg : ext == "dxf" ? .dxf : .other
    }

    private static func coordinated(_ url: URL, _ body: (URL) -> Void) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var err: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: .withoutChanges, error: &err, byAccessor: body)
    }

    private static var cacheDir: URL {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("drawings", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Converts once per file version (cached by name, size and date); call off the main thread.
    static func convert(_ url: URL, dwg: Bool) -> Swift.Result<Result, Error> {
        var size = 0, date = 0.0
        coordinated(url) { real in
            let a = try? FileManager.default.attributesOfItem(atPath: real.path)
            size = (a?[.size] as? Int) ?? 0
            date = (a?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        }
        let id = "v2-" + Store.hash("\(url.lastPathComponent)|\(size)|\(date)")
        let dir = cacheDir
        let pdf = dir.appendingPathComponent(id + ".pdf"), info = dir.appendingPathComponent(id + ".txt")
        if let text = try? String(contentsOf: info, encoding: .utf8), FileManager.default.fileExists(atPath: pdf.path),
           let r = parse(text, pdf) {
            return .success(r)
        }
        // keep the cache small: the 20 newest files
        if let old = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) {
            let sorted = old.sorted {
                ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) >
                ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
            }
            for f in sorted.dropFirst(20) { try? FileManager.default.removeItem(at: f) }
        }
        let src = dir.appendingPathComponent(dwg ? "source.dwg" : "source.dxf")
        try? FileManager.default.removeItem(at: src)
        var copied = false
        coordinated(url) { real in copied = (try? FileManager.default.copyItem(at: real, to: src)) != nil }
        guard copied else { return .failure(ConvertError("The file could not be read.")) }
        defer { try? FileManager.default.removeItem(at: src) }
        let tmp = dir.appendingPathComponent("converting.pdf"), tmpDxf = dir.appendingPathComponent("converting.dxf")
        try? FileManager.default.removeItem(at: tmp)
        let font = Bundle.main.path(forResource: "NotoSans-Regular", ofType: "ttf")
        let geo = Bundle.main.path(forResource: "NotoSansGeorgian-Regular", ofType: "ttf")
        let out = UnsafeMutablePointer<DxfPages>.allocate(capacity: 1)
        defer { out.deallocate() }
        let n = calque_convert(src.path, dwg ? 1 : 0, tmpDxf.path, tmp.path, font, geo, out)
        guard n > 0 else {
            try? FileManager.default.removeItem(at: tmp)
            let why: String
            switch -n {
            case 2: why = "it is a binary DXF. Save it again as an ASCII (text) DXF."
            case 3: why = "it is not a DXF drawing."
            case 4: why = "there is nothing visible in it."
            case 6: why = "the drawing is too big for this device's memory."
            case 7: why = "this DWG version or file could not be read. Try saving it as DXF."
            default: why = "it could not be read."
            }
            return .failure(ConvertError("Cannot open this \(dwg ? "DWG" : "DXF"): " + why))
        }
        var text = "\(n)\n"
        for i in 0..<Int(n) {
            let name = String(cString: calque_page_name(out, Int32(i)))
            text += String(calque_page_ratio(out, Int32(i))) + "\t" + name + "\n"
        }
        try? FileManager.default.removeItem(at: pdf)
        try? FileManager.default.moveItem(at: tmp, to: pdf)
        try? text.write(to: info, atomically: true, encoding: .utf8)
        guard let r = parse(text, pdf) else { return .failure(ConvertError("The converted drawing could not be read.")) }
        return .success(r)
    }

    private static func parse(_ text: String, _ pdf: URL) -> Result? {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        guard let n = Int(lines.first ?? ""), n > 0, lines.count > n else { return nil }
        var ratios: [Double] = [], names: [String?] = []
        for i in 1...n {
            let parts = lines[i].split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            ratios.append(Double(parts.first ?? "") ?? 100)
            let name = parts.count > 1 ? String(parts[1]) : ""
            names.append(name.isEmpty ? nil : name)
        }
        return Result(pdf: pdf, ratios: ratios, names: names)
    }

    struct ConvertError: LocalizedError {
        let message: String
        init(_ m: String) { message = m }
        var errorDescription: String? { message }
    }
}
