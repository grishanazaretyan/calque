import UIKit

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: alpha)
    }

    static func dynamic(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) }
    }
}

/// Colours and small view helpers shared by every screen.
enum Theme {
    static let background = UIColor.dynamic(0xE7E9EE, 0x111317)
    static let chip = UIColor.dynamic(0xE3E6EB, 0x2C3037)
    static let accent = UIColor.dynamic(0xB3480A, 0xFFB68A)
    static let onAccent = UIColor.dynamic(0xFFFFFF, 0x3A1A00)
    /// Measuring marks keep one strong colour that reads on any drawing.
    static let mark = UIColor(hex: 0xFF6A13)
    static let cross = UIColor(hex: 0xE0115F)
    static let selection = UIColor(hex: 0x1E6FE0)
    static let markColors: [UInt32] = [0xE53935, 0x1E6FE0, 0x1E9E4A, 0xFF9800, 0x111111]

    static func apply(to window: UIWindow?) {
        switch Store.theme {
        case 1: window?.overrideUserInterfaceStyle = .light
        case 2: window?.overrideUserInterfaceStyle = .dark
        default: window?.overrideUserInterfaceStyle = .unspecified
        }
    }

    static func applyToAllWindows() {
        for scene in UIApplication.shared.connectedScenes {
            guard let ws = scene as? UIWindowScene else { continue }
            for w in ws.windows { apply(to: w) }
        }
    }

    /// A floating, rounded, translucent bar.
    static func bar(radius: CGFloat) -> UIVisualEffectView {
        let v = UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterial))
        v.layer.cornerRadius = radius
        v.layer.cornerCurve = .continuous
        v.layer.borderWidth = 0.5
        v.layer.borderColor = UIColor.separator.cgColor
        v.clipsToBounds = true
        v.translatesAutoresizingMaskIntoConstraints = false
        return v
    }

    static func iconButton(_ symbol: String, _ label: String, size: CGFloat = 18) -> UIButton {
        var c = UIButton.Configuration.plain()
        c.image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .medium))
        c.baseForegroundColor = .label
        let b = UIButton(configuration: c)
        b.accessibilityLabel = label
        b.translatesAutoresizingMaskIntoConstraints = false
        return b
    }

    static func pill(_ title: String, filled: Bool) -> UIButton {
        var c = filled ? UIButton.Configuration.filled() : UIButton.Configuration.filled()
        c.title = title
        c.cornerStyle = .capsule
        c.baseBackgroundColor = filled ? accent : chip
        c.baseForegroundColor = filled ? onAccent : .label
        c.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)
        let b = UIButton(configuration: c)
        b.translatesAutoresizingMaskIntoConstraints = false
        return b
    }

    static func label(_ text: String, _ size: CGFloat, _ weight: UIFont.Weight = .regular,
                      _ color: UIColor = .label) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = .systemFont(ofSize: size, weight: weight)
        l.textColor = color
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }

    /// "A1 landscape" for standard sheets, otherwise the size in millimetres.
    static func paper(_ size: CGSize) -> String {
        let w = Int((size.width * 25.4 / 72).rounded()), h = Int((size.height * 25.4 / 72).rounded())
        let lo = min(w, h), hi = max(w, h)
        let iso = [(841, 1189), (594, 841), (420, 594), (297, 420), (210, 297), (148, 210)]
        for (i, s) in iso.enumerated() where abs(lo - s.0) <= 3 && abs(hi - s.1) <= 3 {
            return "A\(i) " + (w > h ? "landscape" : "portrait")
        }
        return "\(w) × \(h) mm"
    }
}
