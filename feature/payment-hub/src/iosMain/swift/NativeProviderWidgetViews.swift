import Shared
import SwiftUI

/// A third-party widget on the Hub. The size decides what it offers: a small tile is one tap (the
/// saved item, or amount entry); larger sizes offer a few choices, with the rest behind "⋯".
struct ProviderTileFace: View {
    let provider: NativeProviderTile
    let interactive: Bool
    let pay: (String) -> Void
    /// Asks the Hub to present `more` or `amount`; sheets live above the grid.
    var openSheet: (String) -> Void = { _ in }

    private var small: Bool { provider.size == "small" }
    private var large: Bool { provider.size == "large" }
    private var palette: TilePalette { .tinted(provider.tint) }

    /// Choices shown on the tile; "More" and "Other amount" move to the corner.
    private var choices: [NativeProviderAction] { provider.actions.filter { $0.kind == "pay" } }
    private var overflow: NativeProviderAction? { provider.actions.first { $0.kind != "pay" } }
    /// A small tile with one thing to do is itself the button.
    private var single: NativeProviderAction? { small && provider.actions.count == 1 ? provider.actions.first : nil }

    var body: some View {
        if let single {
            Button { run(single) } label: { face }
                .buttonStyle(TilePressStyle())
                .disabled(!interactive)
                .accessibilityLabel([provider.title, provider.headline, single.title].joined(separator: ", "))
        } else {
            face
        }
    }

    private var face: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Every tile reads from the top: who, then the hero. The bottom edge is left to the
            // trend or the choices.
            topRow
            hero.padding(.top, 8)
            notice
            Spacer(minLength: 8)
            if single == nil, !choices.isEmpty {
                chips
            }
        }
        .padding(HubTileStyle.padding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(alignment: .bottom) { trendLine }
        .tileSurface(palette)
        .contentShape(RoundedRectangle(cornerRadius: HubTileStyle.corner, style: .continuous))
    }

    private var topRow: some View {
        HStack(alignment: .center, spacing: 8) {
            BrandGlyph(icon: provider.icon, monogram: provider.monogram, palette: palette)
            Text(provider.label)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            Spacer(minLength: 4)
            if let change = TrendChange.text(provider.status?.trend.map(\.doubleValue) ?? []) {
                Text(change.0)
                    .font(.caption.weight(.semibold)).monospacedDigit()
                    .foregroundStyle(change.1 ? Color.green : Color.red)
                    .brightness(palette.tinted ? 0.15 : 0)
            } else if let single {
                TapBadge(symbol: Self.symbol(single), palette: palette)
            } else if let overflow {
                Button { run(overflow) } label: {
                    Image(systemName: Self.symbol(overflow))
                        .font(.system(size: 13, weight: .bold))
                        .frame(width: 30, height: 24)
                        .background(palette.chip, in: Capsule())
                        .foregroundStyle(palette.chipText)
                }
                .buttonStyle(.plain)
                .disabled(!interactive)
                .accessibilityLabel(overflow.title)
            }
        }
    }

    /// ⚡ pays the saved item, ⋯ shows every option to pick one, ✎ asks for an amount. Android uses
    /// the matching Material icons: Bolt, MoreHoriz and Edit.
    static func symbol(_ action: NativeProviderAction) -> String {
        switch action.kind {
        case "more": return "ellipsis"
        case "enterAmount": return "pencil"
        default: return "bolt.fill"
        }
    }

    @ViewBuilder
    private var hero: some View {
        let size = HubTileStyle.heroSize(small: small, large: large)
        if let status = provider.status {
            statusHero(status, size: size)
        } else if provider.purchaseType == "product", small {
            VStack(alignment: .leading, spacing: 2) {
                if let price = provider.price {
                    Text(price).font(HubTileStyle.hero(size)).lineLimit(1).minimumScaleFactor(0.5)
                }
                Text(provider.headline)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
        } else if choices.isEmpty, let single {
            // Nothing to show but the action itself, such as checking a bill.
            Text(single.title).font(.title3.weight(.bold)).lineLimit(2).minimumScaleFactor(0.8)
        }
    }

    @ViewBuilder
    private func statusHero(_ status: NativeProviderStatus, size: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let value = status.value {
                Text(value).font(HubTileStyle.hero(size)).lineLimit(1).minimumScaleFactor(0.45)
            } else if status.loading {
                // Like a WidgetKit placeholder: the shape of a value, never a made-up one.
                Text(verbatim: "00,000").font(HubTileStyle.hero(size)).redacted(reason: .placeholder)
                    .accessibilityHidden(true)
            } else {
                Text(verbatim: "—").font(HubTileStyle.hero(size)).foregroundStyle(palette.secondary)
            }
            if let subtitle = status.subtitle, !small || single == nil, status.trend.isEmpty || large {
                Text(subtitle).font(.caption).foregroundStyle(palette.secondary).lineLimit(1)
            }
            // Age only when it matters: a value that missed its refreshes.
            if let observed = status.observedAt, let date = ProviderDates.parse(observed) {
                Label { Text(date, format: .relative(presentation: .named)) } icon: {
                    Image(systemName: "clock.badge.exclamationmark")
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(palette.tinted ? palette.foreground : Color.orange)
                .lineLimit(1)
            }
        }
    }

    @ViewBuilder
    private var notice: some View {
        if let attention = provider.attention {
            Label(attention, systemImage: "exclamationmark.circle.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(palette.tinted ? palette.foreground : Color.orange)
                .lineLimit(1)
                .padding(.top, 4)
        } else if let delivery = provider.delivery {
            Label(delivery, systemImage: provider.deliveryTone == "failure" ? "exclamationmark.triangle.fill" : "clock")
                .font(.caption.weight(.semibold))
                .foregroundStyle(provider.deliveryTone == "failure" && !palette.tinted ? Color.red : palette.accent)
                .lineLimit(1)
                .padding(.top, 4)
        }
    }

    /// Amounts fit in one row; named items get a two-column grid.
    private var chips: some View {
        let named = choices.contains { $0.subtitle != nil }
        let columns = named ? 2 : min(max(choices.count, 1), 4)
        return LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns),
            alignment: .leading,
            spacing: 6
        ) {
            ForEach(choices, id: \.id) { item in
                Button { run(item) } label: {
                    VStack(alignment: named ? .leading : .center, spacing: 1) {
                        Text(item.title)
                            .font(named ? .footnote.weight(.semibold) : HubTileStyle.hero(15))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                        if let subtitle = item.subtitle {
                            Text(subtitle).font(HubTileStyle.hero(12).weight(.semibold)).opacity(0.75).lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: named ? .leading : .center)
                    .padding(.horizontal, named ? 10 : 4)
                    .padding(.vertical, 8)
                    .background(palette.chip, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .foregroundStyle(palette.chipText)
                }
                .buttonStyle(TilePressStyle())
                .disabled(!interactive)
            }
        }
    }

    /// Only tiles without choices draw the trend, so it never sits behind buttons.
    @ViewBuilder
    private var trendLine: some View {
        let points = provider.status?.trend.map(\.doubleValue) ?? []
        if points.count > 1, single != nil || choices.isEmpty {
            Sparkline(points: points, color: palette.tinted ? palette.foreground : Color.accentColor)
                .frame(height: small ? 44 : 56)
                .opacity(0.9)
        }
    }

    private func run(_ action: NativeProviderAction) {
        switch action.kind {
        case "more": openSheet("more")
        case "enterAmount": openSheet("amount")
        default: pay(action.id)
        }
    }
}

struct ProviderSheetRequest: Identifiable {
    let tileId: String
    let kind: String
    var id: String { "\(tileId):\(kind)" }
}

/// "More" and "Other amount" for a provider tile, presented by the Hub.
struct ProviderSheet: View {
    let provider: NativeProviderTile
    let kind: String
    let pay: (String) -> Void
    let payAmount: (String) -> String?

    var body: some View {
        if kind == "amount", let entry = provider.amountEntry {
            ProviderAmountSheet(entry: entry, pay: pay, payAmount: payAmount)
        } else {
            ProviderProductsSheet(provider: provider, buy: pay)
        }
    }
}

/// Paid provider orders, kept on the device with what they delivered.
struct PurchasesView: View {
    let title: String
    let empty: String
    let checkAgain: String
    let purchases: [NativePurchase]
    let check: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                if purchases.isEmpty {
                    Text(empty).foregroundStyle(.secondary)
                }
                ForEach(purchases, id: \.id) { purchase in
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(purchase.item).font(.headline)
                                Spacer()
                                if let amount = purchase.amount {
                                    Text(amount).fontWeight(.semibold)
                                }
                            }
                            Text("\(purchase.provider) · \(purchase.host)")
                                .font(.caption).foregroundStyle(.secondary)
                            HStack {
                                Text(purchase.state)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(color(purchase.tone))
                                Spacer()
                                Text(Date(timeIntervalSince1970: Double(purchase.paidAtMs) / 1000), style: .date)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if let message = purchase.message {
                                Text(message).font(.footnote)
                            }
                        }
                        if let voucher = purchase.voucher {
                            VoucherCard(card: voucher)
                                .listRowInsets(EdgeInsets())
                                .listRowBackground(Color.clear)
                        }
                        ForEach(Array(purchase.items.filter(\.page).enumerated()), id: \.offset) { _, item in
                            RedemptionPage(url: item.value, title: item.label)
                                .listRowInsets(EdgeInsets())
                        }
                        ForEach(Array(purchase.items.filter { !$0.page }.enumerated()), id: \.offset) { _, item in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.label).font(.caption).foregroundStyle(.secondary)
                                    Text(item.value)
                                        .font(item.copyable ? .body.monospaced() : .body)
                                        .textSelection(.enabled)
                                }
                                Spacer()
                                if item.copyable {
                                    Button {
                                        UIPasteboard.general.string = item.value
                                    } label: {
                                        Image(systemName: "doc.on.doc")
                                    }
                                    .buttonStyle(.borderless)
                                }
                            }
                        }
                        if purchase.tone != "success" {
                            Button(checkAgain) { check(purchase.id) }
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func color(_ tone: String) -> Color {
        switch tone {
        case "success": return .green
        case "failure": return .red
        default: return .orange
        }
    }
}

/// Every product, to buy one once without changing what the widget's small form buys.
private struct ProviderProductsSheet: View {
    let provider: NativeProviderTile
    let buy: (String) -> Void

    var body: some View {
        NavigationStack {
            List(provider.products, id: \.id) { product in
                Button { buy("product:\(product.id)") } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(product.name)
                            if let detail = product.detail {
                                Text(detail).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text(product.price).fontWeight(.semibold)
                    }
                }
                .foregroundStyle(product.available ? Color.primary : Color.secondary)
                .disabled(!product.available)
            }
            .navigationTitle(provider.title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

/// Amount entry for `amount` purchases: presets plus a free amount within the provider's range.
private struct ProviderAmountSheet: View {
    let entry: NativeProviderAmountEntry
    let pay: (String) -> Void
    let payAmount: (String) -> String?

    @State private var text = ""
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                if !entry.presets.isEmpty {
                    Section {
                        HStack {
                            ForEach(entry.presets, id: \.id) { preset in
                                Button(preset.title) { pay(preset.id) }
                                    .buttonStyle(.bordered)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }
                Section {
                    HStack {
                        Text(entry.currencyCode).foregroundStyle(.secondary)
                        TextField("0", text: $text)
                            .keyboardType(entry.fractionDigits > 0 ? .decimalPad : .numberPad)
                            .focused($focused)
                            .font(.title2.weight(.semibold))
                    }
                } footer: {
                    Text(error ?? entry.range).foregroundStyle(error == nil ? Color.secondary : Color.red)
                }
                Section {
                    Button(entry.payTitle) { error = payAmount(text) }
                        .frame(maxWidth: .infinity)
                        .fontWeight(.semibold)
                        .disabled(text.isEmpty)
                }
            }
            .navigationTitle(entry.title)
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }
}

enum ProviderDates {
    private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
    private static let plain = ISO8601DateFormatter()

    static func parse(_ value: String) -> Date? {
        fractional.date(from: value) ?? plain.date(from: value)
    }
}

/// One-screen setup for a widget added by link.
struct ProviderSetupView: View {
    let controller: NativePaymentHubController
    let setup: NativeProviderSetup
    let dismissTitle: String
    @FocusState private var focusedInput: String?

    var body: some View {
        Group {
            switch setup.phase {
            case "ready": ready
            case "families": ProviderFamilyGallery(controller: controller, setup: setup)
            case "failed":
                ContentUnavailableView {
                    Label(setup.host, systemImage: "wifi.exclamationmark")
                } description: {
                    Text(setup.message ?? "")
                } actions: {
                    Button(setup.retryTitle) { controller.startProviderSetup(link: setup.host) }
                }
            default:
                ProgressView(setup.message ?? "").frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { controller.close() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(dismissTitle)
            }
        }
    }

    private var ready: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    BrandGlyph(icon: setup.icon, monogram: setup.monogram, palette: .neutral, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        // The host is the identity; the name is only what the provider claims.
                        Text(setup.host).font(.headline)
                        if let callsItself = setup.callsItself {
                            Text(callsItself).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if let warning = setup.unreviewedWarning {
                    Label(warning, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                if let privacy = setup.privacy {
                    Label(privacy, systemImage: "lock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if !setup.products.isEmpty || setup.emptyProducts != nil {
                Section(setup.chooseTitle) {
                    if let empty = setup.emptyProducts {
                        Text(empty).foregroundStyle(.secondary)
                    }
                    ForEach(setup.products, id: \.id) { product in
                        Button {
                            focusedInput = nil
                            controller.selectProviderProduct(id: product.id)
                        } label: {
                            HStack {
                                Image(systemName: product.selected ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(product.selected ? Color.accentColor : .secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(product.name)
                                    if let detail = product.detail {
                                        Text(detail).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Text(product.price).font(.body.weight(.semibold))
                            }
                        }
                        .foregroundStyle(product.available ? Color.primary : Color.secondary)
                        .disabled(!product.available)
                    }
                }
            }

            ForEach(setup.inputs, id: \.id) { input in
                Section {
                    HStack {
                        if let prefix = input.prefix {
                            Text(prefix).foregroundStyle(.secondary)
                        }
                        field(input)
                    }
                    .listRowBackground(input.invalid ? Color.red.opacity(0.08) : nil)
                } header: {
                    Text(input.label)
                } footer: {
                    if let message = input.message {
                        Text(message).foregroundStyle(.red)
                    }
                }
            }

            if setup.sizes.count > 1 {
            Section(setup.sizeTitle) {
                Picker(
                    setup.sizeTitle,
                    selection: Binding(
                        get: { setup.selectedSize },
                        set: { controller.selectProviderSize(id: $0) }
                    )
                ) {
                    ForEach(setup.sizes, id: \.id) { size in
                        Text(size.title).tag(size.id)
                    }
                }
                .pickerStyle(.segmented)
            }
            }

            Section {
                if let error = setup.error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
                Button {
                    focusedInput = nil
                    controller.saveProviderWidget()
                } label: {
                    HStack {
                        Spacer()
                        if setup.busy { ProgressView() } else { Text(setup.saveTitle) }
                        Spacer()
                    }
                }
                .fontWeight(.semibold)
                .disabled(setup.busy)
                if let terms = setup.termsText {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(terms).font(.footnote).foregroundStyle(.secondary)
                        if let url = setup.termsUrl.flatMap(URL.init(string:)) {
                            Link(setup.termsTitle, destination: url).font(.footnote)
                        }
                    }
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(setup.doneTitle) { focusedInput = nil }
            }
        }
        .navigationTitle(setup.title)
    }

    @ViewBuilder
    private func field(_ input: NativeProviderInput) -> some View {
        let binding = Binding(
            get: { input.value },
            set: { controller.updateProviderInput(id: input.id, value: $0) }
        )
        if input.type == "choice" {
            // A fixed list, such as the electricity distributor: pick, never type.
            Picker(input.label, selection: binding) {
                if input.value.isEmpty { Text(input.placeholder ?? "—").tag("") }
                ForEach(input.options, id: \.value) { option in
                    Text(option.label).tag(option.value)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if input.secret {
            SecureField(input.placeholder ?? input.label, text: binding)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedInput, equals: input.id)
        } else {
            TextField(input.placeholder ?? input.label, text: binding)
                .keyboardType(keyboard(input.type))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedInput, equals: input.id)
        }
    }

    private func keyboard(_ type: String) -> UIKeyboardType {
        switch type {
        case "phone": return .phonePad
        case "number": return .decimalPad
        case "email": return .emailAddress
        default: return .default
        }
    }
}

/// The family chooser: swipe through the sizes a provider supports and see what each will show.
private struct ProviderFamilyGallery: View {
    let controller: NativePaymentHubController
    let setup: NativeProviderSetup
    @State private var selection = ""

    private var rows: CGFloat { setup.families.contains { $0.id == "large" } ? 2 : 1 }

    var body: some View {
        GeometryReader { proxy in
            let gaps = HubGeometry.gap * (rows - 1)
            let budget = max(proxy.size.height * 0.45, 160)
            let unit = min((min(proxy.size.width, 420) - 52) / 2, (budget - gaps) / rows, 160)
            let stage = unit * rows + gaps
            ScrollView {
                VStack(spacing: 14) {
                    Spacer(minLength: 0)
                    BrandGlyph(icon: setup.icon, monogram: setup.monogram, palette: .neutral, size: 44)
                    Text(setup.title).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                    if let callsItself = setup.callsItself {
                        Text("\(setup.host) · \(callsItself)")
                            .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    TabView(selection: $selection) {
                        ForEach(setup.families, id: \.id) { family in
                            Group {
                                if let preview = family.preview {
                                    ProviderTileFace(provider: preview, interactive: false, pay: { _ in })
                                        .allowsHitTesting(false)
                                }
                            }
                            .frame(
                                width: HubGeometry.span(unit, family.id == "small" ? 1 : 2),
                                height: HubGeometry.span(unit, family.id == "large" ? 2 : 1)
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .tag(family.id)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: setup.families.count > 1 ? .always : .never))
                    .indexViewStyle(.page(backgroundDisplayMode: .always))
                    .frame(height: stage + (setup.families.count > 1 ? 44 : 0))
                    if let family = setup.families.first(where: { $0.id == selection }) {
                        Text(family.title).font(.headline)
                        Text(family.detail)
                            .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
        .safeAreaInset(edge: .bottom) {
            HubPrimaryButton(title: setup.addTitle, busy: false) {
                controller.chooseProviderFamily(id: selection)
            }
        }
        .onAppear { if selection.isEmpty { selection = setup.selectedFamily } }
    }
}
