import SwiftUI
import UIKit

/// The Hub's visual language, shared by people and provider tiles: one hero per tile, identity as
/// a small glyph in the corner, and a surface that is neutral or the provider's own colour.
enum HubTileStyle {
    static let corner: CGFloat = 26
    static let padding: CGFloat = 14
    static let glyph: CGFloat = 24

    /// Amounts and values are the Hub's signature: large, rounded, with steady digits.
    static func hero(_ size: CGFloat) -> Font {
        .system(size: size, weight: .bold, design: .rounded).monospacedDigit()
    }

    static func heroSize(small: Bool, large: Bool) -> CGFloat {
        large ? 40 : (small ? 30 : 34)
    }
}

/// A tile's colours: its surface, the text on it, and the quieter fills for chips and badges.
struct TilePalette {
    let surface: AnyShapeStyle
    let foreground: Color
    let secondary: Color
    let chip: Color
    let chipText: Color
    let accent: Color
    let tinted: Bool

    static let neutral = TilePalette(
        surface: AnyShapeStyle(Color(uiColor: .secondarySystemGroupedBackground)),
        foreground: .primary,
        secondary: .secondary,
        chip: Color.accentColor.opacity(0.12),
        chipText: .accentColor,
        accent: .accentColor,
        tinted: false
    )

    /// A provider's tint as a soft vertical gradient, with text that stays readable on it.
    static func tinted(_ hex: String?) -> TilePalette {
        guard let hex, let base = UIColor(hex: hex) else { return .neutral }
        let text: Color = base.isLight ? .black : .white
        return TilePalette(
            surface: AnyShapeStyle(
                LinearGradient(
                    colors: [Color(uiColor: base.adjusted(brightness: 0.08)), Color(uiColor: base.adjusted(brightness: -0.10))],
                    startPoint: .top,
                    endPoint: .bottom
                )
            ),
            foreground: text,
            secondary: text.opacity(0.72),
            chip: text.opacity(0.16),
            chipText: text,
            accent: text,
            tinted: true
        )
    }
}

extension View {
    /// The tile shape every Hub widget shares.
    func tileSurface(_ palette: TilePalette) -> some View {
        background(palette.surface, in: RoundedRectangle(cornerRadius: HubTileStyle.corner, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: HubTileStyle.corner, style: .continuous))
            .foregroundStyle(palette.foreground)
    }
}

/// A whole tile as one button, slightly giving way under the finger.
struct TilePressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

/// Who a tile is about: the provider's icon, or its initial when it has none or is not reviewed.
struct BrandGlyph: View {
    let icon: String?
    let monogram: String
    let palette: TilePalette
    var size: CGFloat = HubTileStyle.glyph

    var body: some View {
        Group {
            if let image = icon.flatMap(BrandImages.image) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(size * 0.1)
                    .frame(width: size, height: size)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
            } else {
                Text(monogram)
                    .font(.system(size: size * 0.52, weight: .bold, design: .rounded))
                    .foregroundStyle(palette.tinted ? palette.foreground : Color.white)
                    .frame(width: size, height: size)
                    .background(
                        palette.tinted ? palette.chip : Color.accentColor,
                        in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                    )
            }
        }
        .accessibilityHidden(true)
    }
}

/// The small mark on tiles that pay with one tap.
struct TapBadge: View {
    let symbol: String
    let palette: TilePalette

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(palette.tinted ? palette.foreground : Color.accentColor)
            .frame(width: 24, height: 24)
            .background(palette.chip, in: Circle())
            .accessibilityHidden(true)
    }
}

/// Recent values as a line with a soft fill, drawn edge to edge along the bottom of a tile.
struct Sparkline: View {
    let points: [Double]
    let color: Color

    var body: some View {
        GeometryReader { proxy in
            let path = line(in: proxy.size)
            ZStack {
                area(in: proxy.size, line: path)
                    .fill(LinearGradient(colors: [color.opacity(0.28), color.opacity(0)], startPoint: .top, endPoint: .bottom))
                path.stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
        }
        .accessibilityHidden(true)
    }

    private func line(in size: CGSize) -> Path {
        guard let low = points.min(), let high = points.max(), points.count > 1 else { return Path() }
        let span = max(high - low, .ulpOfOne)
        let step = size.width / CGFloat(points.count - 1)
        var path = Path()
        for (index, value) in points.enumerated() {
            let point = CGPoint(
                x: CGFloat(index) * step,
                y: size.height * (1 - CGFloat((value - low) / span)) * 0.85 + size.height * 0.05
            )
            index == 0 ? path.move(to: point) : path.addLine(to: point)
        }
        return path
    }

    private func area(in size: CGSize, line: Path) -> Path {
        var path = line
        path.addLine(to: CGPoint(x: size.width, y: size.height))
        path.addLine(to: CGPoint(x: 0, y: size.height))
        path.closeSubpath()
        return path
    }
}

enum TrendChange {
    /// The change from the first to the last point, like "+1.24%".
    static func text(_ points: [Double]) -> (String, Bool)? {
        guard let first = points.first, let last = points.last, points.count > 1, first != 0 else { return nil }
        let change = (last - first) / abs(first)
        return (change.formatted(.percent.precision(.fractionLength(2)).sign(strategy: .always())), change >= 0)
    }
}

/// Decoded provider icons, kept so tiles do not decode on every render.
enum BrandImages {
    private static let cache = NSCache<NSString, UIImage>()

    static func image(_ base64: String) -> UIImage? {
        let key = base64 as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let data = Data(base64Encoded: base64), let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }
}

extension UIColor {
    convenience init?(hex: String) {
        let digits = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else { return nil }
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }

    /// Relative luminance above the point where black text reads better than white.
    var isLight: Bool {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        func linear(_ c: CGFloat) -> CGFloat { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue) > 0.4
    }

    func adjusted(brightness delta: CGFloat) -> UIColor {
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
        guard getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha) else { return self }
        return UIColor(hue: hue, saturation: saturation, brightness: min(max(brightness + delta, 0), 1), alpha: alpha)
    }
}
