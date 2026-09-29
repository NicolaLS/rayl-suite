import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI
import UIKit

/// A voucher drawn natively: the product's brand is the card and its value the hero, followed by
/// what the user needs at the till or in the app: code, PIN, expiry and barcode.
struct VoucherCard: View {
    let card: NativeVoucherCard
    @State private var revealed: Set<String> = []
    @State private var copied: String?
    @State private var enlarged = false

    private var palette: TilePalette { .tinted(card.tint) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                BrandGlyph(icon: card.icon, monogram: card.monogram, palette: palette, size: 40)
                Spacer(minLength: 8)
                if let value = card.value {
                    Text(value).font(HubTileStyle.hero(34)).lineLimit(1).minimumScaleFactor(0.5)
                }
            }
            Text(card.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.secondary)
            Rectangle()
                .fill(palette.secondary.opacity(0.35))
                .frame(height: 1)
            ForEach(card.credentials, id: \.label) { credential in
                credentialRow(credential)
            }
            if let expiry = card.expiry, let date = VoucherDates.parse(expiry) {
                VStack(alignment: .leading, spacing: 2) {
                    Text((card.expiryLabel ?? "").uppercased())
                        .font(.caption2.weight(.semibold)).foregroundStyle(palette.secondary)
                    Text(date, format: .dateTime.day().month(.abbreviated).year())
                        .font(.subheadline.weight(.semibold))
                }
            }
            if let barcode = card.barcode, let image = BarcodeImage.make(barcode, format: card.barcodeFormat) {
                Button { enlarged = true } label: {
                    BarcodePanel(image: image, value: barcode, square: card.barcodeFormat.isSquareBarcode, height: 96)
                }
                .buttonStyle(TilePressStyle())
                .accessibilityLabel(barcode)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .foregroundStyle(palette.foreground)
        .sheet(isPresented: $enlarged) {
            if let barcode = card.barcode, let image = BarcodeImage.make(barcode, format: card.barcodeFormat) {
                EnlargedBarcode(image: image, value: barcode, square: card.barcodeFormat.isSquareBarcode)
            }
        }
    }

    private func credentialRow(_ credential: NativeVoucherCredential) -> some View {
        let hidden = credential.secret && !revealed.contains(credential.label)
        return VStack(alignment: .leading, spacing: 2) {
            Text(credential.label.uppercased())
                .font(.caption2.weight(.semibold))
                .foregroundStyle(palette.secondary)
            HStack(spacing: 10) {
                Text(hidden ? String(repeating: "•", count: max(4, min(credential.value.count, 8))) : credential.value)
                    .font(.title3.weight(.bold).monospaced())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .textSelection(.enabled)
                Spacer(minLength: 4)
                if credential.secret {
                    chipButton(hidden ? "eye" : "eye.slash") {
                        if hidden { revealed.insert(credential.label) } else { revealed.remove(credential.label) }
                    }
                }
                chipButton(copied == credential.label ? "checkmark" : "doc.on.doc") {
                    UIPasteboard.general.string = credential.value
                    copied = credential.label
                }
                .accessibilityLabel(credential.label)
            }
        }
    }

    private func chipButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 34, height: 30)
                .background(palette.chip, in: Capsule())
                .foregroundStyle(palette.chipText)
        }
        .buttonStyle(.plain)
    }
}

/// Barcodes need a white field and crisp pixels to scan, whatever the card colour.
private struct BarcodePanel: View {
    let image: UIImage
    let value: String
    let square: Bool
    let height: CGFloat

    var body: some View {
        VStack(spacing: 6) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: square ? height : .infinity, maxHeight: height)
            Text(value).font(.caption.monospaced()).foregroundStyle(.black).lineLimit(1).minimumScaleFactor(0.6)
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Shown to a cashier: large, on white, at full brightness until dismissed.
private struct EnlargedBarcode: View {
    let image: UIImage
    let value: String
    let square: Bool
    @State private var previousBrightness: CGFloat?

    var body: some View {
        BarcodePanel(image: image, value: value, square: square, height: square ? 280 : 160)
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.white)
            .presentationDetents([.medium, .large])
            .onAppear {
                guard let screen = Self.screen else { return }
                previousBrightness = screen.brightness
                screen.brightness = 1
            }
            .onDisappear {
                if let previousBrightness { Self.screen?.brightness = previousBrightness }
            }
    }

    private static var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen
    }
}

enum BarcodeImage {
    /// Draws `qr`, `code128`, `pdf417` or `aztec`; anything else is not drawn.
    static func make(_ value: String, format: String?) -> UIImage? {
        let message = Data(value.utf8)
        let output: CIImage?
        switch format {
        case "qr":
            let filter = CIFilter.qrCodeGenerator()
            filter.message = message
            filter.correctionLevel = "M"
            output = filter.outputImage
        case "code128":
            let filter = CIFilter.code128BarcodeGenerator()
            filter.message = message
            filter.quietSpace = 7
            output = filter.outputImage
        case "pdf417":
            let filter = CIFilter.pdf417BarcodeGenerator()
            filter.message = message
            output = filter.outputImage
        case "aztec":
            let filter = CIFilter.aztecCodeGenerator()
            filter.message = message
            output = filter.outputImage
        default:
            output = nil
        }
        guard let output else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let image = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

enum VoucherDates {
    private static let formatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withFullDate]
        return formatter
    }()

    static func parse(_ value: String) -> Date? { formatter.date(from: String(value.prefix(10))) }
}

private extension Optional where Wrapped == String {
    var isSquareBarcode: Bool { self == "qr" || self == "aztec" }
}
