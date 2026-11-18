import UIKit

/// A mark drawn over a sheet. Points and sizes are in the sheet's own PDF points.
final class MarkItem: Codable {
    static let pen = 0, arrow = 1, box = 2, text = 3

    var type: Int
    var page: Int
    var color: UInt32
    /// Line width, or text size, in PDF points.
    var width: Double
    var pts: [CGPoint]
    var text: String?

    init(type: Int, page: Int, color: UInt32, width: Double, pts: [CGPoint] = [], text: String? = nil) {
        self.type = type
        self.page = page
        self.color = color
        self.width = width
        self.pts = pts
        self.text = text
    }

    static func load(_ url: URL) -> [MarkItem] {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([MarkItem].self, from: data) else { return [] }
        return list
    }

    static func save(_ items: [MarkItem], to url: URL) {
        if items.isEmpty { try? FileManager.default.removeItem(at: url); return }
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: url, options: .atomic) }
    }
}

/// A finished measurement kept on the sheet.
struct Measurement: Codable {
    var page: Int
    var area: Bool
    var pts: [CGPoint]

    static func load(_ url: URL) -> [Measurement] {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([Measurement].self, from: data) else { return [] }
        return list
    }

    static func save(_ list: [Measurement], to url: URL) {
        if list.isEmpty { try? FileManager.default.removeItem(at: url); return }
        if let data = try? JSONEncoder().encode(list) { try? data.write(to: url, options: .atomic) }
    }
}

/// One undo step of the markup.
enum MarkChange {
    case added(MarkItem)
    case removed(MarkItem, Int)
    case edited(MarkItem, text: String?, width: Double, color: UInt32, origin: CGPoint)
}
