import Shared
import SwiftUI

@MainActor
private final class NativePaymentHubModel: ObservableObject {
    @Published private(set) var snapshot: NativePaymentHubSnapshot?
    let controller: NativePaymentHubController
    private var cancel: (() -> Void)?

    init(controller: NativePaymentHubController) {
        self.controller = controller
        cancel = controller.observe { [weak self] value in
            self?.snapshot = value
        }
    }

    deinit { cancel?() }
}

private enum HubRoute: Hashable {
    case variants
    case configure
    case providerSetup
}

enum HubGeometry {
    static let gap: CGFloat = 12
    static let gutter: CGFloat = 16

    static func span(_ unit: CGFloat, _ count: Int32) -> CGFloat {
        unit * CGFloat(count) + gap * CGFloat(max(count - 1, 0))
    }
}

/// The app owns payment behavior; the Hub presents independently configured native widgets.
struct NativePaymentHubView: View {
    @StateObject private var model: NativePaymentHubModel
    @State private var hoveredId: String?
    @State private var providerSheet: ProviderSheetRequest?
    @State private var showingPurchases = false
    init(controller: NativePaymentHubController) {
        _model = StateObject(wrappedValue: NativePaymentHubModel(controller: controller))
    }

    var body: some View {
        Group {
            if let state = model.snapshot {
                canvas(state)
            } else {
                ProgressView()
            }
        }
        .sheet(isPresented: editorPresented) {
            HubEditorSheet(model: model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(model.snapshot?.busy == true)
        }
        .sheet(item: $providerSheet) { request in
            if let provider = model.snapshot?.canvas.tiles.first(where: { $0.id == request.tileId })?.provider {
                ProviderSheet(
                    provider: provider,
                    kind: request.kind,
                    pay: { action in
                        providerSheet = nil
                        model.controller.payProvider(widgetId: request.tileId, actionId: action)
                    },
                    payAmount: { text in
                        let error = model.controller.payProviderAmount(widgetId: request.tileId, text: text)
                        if error == nil { providerSheet = nil }
                        return error
                    }
                )
            }
        }
        .sheet(isPresented: $showingPurchases) {
            if let state = model.snapshot {
                PurchasesView(
                    title: state.text.purchases,
                    empty: state.text.purchasesEmpty,
                    checkAgain: state.text.checkAgain,
                    purchases: state.purchases,
                    check: { model.controller.checkPurchase(id: $0) }
                )
            }
        }
    }

    private var editorPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot.map { $0.screen != "hub" } ?? false },
            set: { if !$0 { model.controller.close() } }
        )
    }

    private func canvas(_ state: NativePaymentHubSnapshot) -> some View {
        GeometryReader { viewport in
            let columns = viewport.size.width >= 700 ? 4 : 2
            let width = viewport.size.width - HubGeometry.gutter * 2
            let unit = (width - HubGeometry.gap * CGFloat(columns - 1)) / CGFloat(columns)
            ScrollView {
                Group {
                    if state.canvas.tiles.isEmpty {
                        // A lone dashed square on an empty screen reads as a loading failure,
                        // so the first run says what the canvas is for.
                        emptyCanvas(state, unit: min(unit, 180))
                            .frame(minHeight: viewport.size.height)
                    } else {
                        widgetGrid(state, unit: unit)
                            .padding(.top, HubGeometry.gutter)
                            .padding(.bottom, 24)
                    }
                }
                .padding(.horizontal, HubGeometry.gutter)
                .disabled(state.busy)
            }
            // The header floats: tiles scroll underneath the purchases button, not up to an edge.
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    HStack {
                        if state.canvas.arranging {
                            Text(state.text.editHub).font(.headline)
                        }
                        Spacer()
                        if state.canvas.arranging {
                            Button(state.text.done) { model.controller.setArranging(value: false) }
                                .buttonStyle(.borderedProminent)
                        } else if !state.purchases.isEmpty {
                            Button { showingPurchases = true } label: {
                                Image(systemName: "bag")
                                    .font(.title3)
                                    .overlay(alignment: .topTrailing) {
                                        if state.purchases.contains(where: { $0.tone != "success" }) {
                                            Circle().fill(Color.orange).frame(width: 9, height: 9).offset(x: 4, y: -2)
                                        }
                                    }
                            }
                            .floatingButton()
                            .accessibilityLabel(state.text.purchases)
                        }
                    }
                    .padding(.horizontal, HubGeometry.gutter)
                    .padding(.top, 8)
                    if let error = state.error, state.screen == "hub" {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, HubGeometry.gutter)
                            .padding(.bottom, 12)
                    }
                }
            }
            .softTopEdge()
            .refreshable { model.controller.refreshProviderStatus() }
            .onAppear { model.controller.setCanvasColumns(value: Int32(columns)) }
            .onChange(of: columns) { _, value in
                model.controller.setCanvasColumns(value: Int32(value))
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }

    private func widgetGrid(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        let addPosition = addWidgetPosition(state.canvas)
        return ZStack(alignment: .topLeading) {
            ForEach(Array(state.canvas.tiles.enumerated()), id: \.element.id) { index, tile in
                WidgetContent(
                    tile: tile,
                    copy: state.text,
                    interactive: !state.canvas.arranging,
                    pay: { model.controller.pay(actionId: $0) },
                    payProvider: { model.controller.payProvider(widgetId: tile.id, actionId: $0) },
                    openProviderSheet: { providerSheet = ProviderSheetRequest(tileId: tile.id, kind: $0) }
                )
                .overlay(alignment: .topLeading) {
                    if state.canvas.arranging {
                        Button { model.controller.removeWidget(id: tile.id) } label: {
                            Image(systemName: "minus.circle.fill")
                                .font(.title2)
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .red)
                        }
                        .offset(x: -8, y: -8)
                        .accessibilityLabel(state.text.remove)
                    }
                }
                .frame(
                    width: HubGeometry.span(unit, tile.columns),
                    height: HubGeometry.span(unit, tile.rows)
                )
                .overlay {
                    if hoveredId == tile.id {
                        RoundedRectangle(cornerRadius: 24)
                            .strokeBorder(Color.accentColor, lineWidth: 3)
                    }
                }
                .animation(.snappy(duration: 0.22), value: tile.row)
                .animation(.snappy(duration: 0.22), value: tile.column)
                .contextMenu { widgetActions(tile, index: index, state: state) }
                .accessibilityAction(named: Text(state.text.editWidget)) {
                    model.controller.editWidget(id: tile.id)
                }
                .accessibilityAction(named: Text(state.text.moveUp)) {
                    model.controller.moveWidgetBy(id: tile.id, offset: -1)
                }
                .accessibilityAction(named: Text(state.text.moveDown)) {
                    model.controller.moveWidgetBy(id: tile.id, offset: 1)
                }
                .accessibilityAction(named: Text(state.text.remove)) {
                    model.controller.removeWidget(id: tile.id)
                }
                .draggable(tile.id)
                .dropDestination(for: String.self) { ids, _ in
                    guard !state.busy, ids.count == 1, let id = ids.first,
                        id != tile.id, state.canvas.tiles.contains(where: { $0.id == id })
                    else {
                        return false
                    }
                    model.controller.moveWidget(id: id, onto: tile.id)
                    hoveredId = nil
                    return true
                } isTargeted: { targeted in
                    if targeted {
                        hoveredId = tile.id
                    } else if hoveredId == tile.id {
                        hoveredId = nil
                    }
                }
                .offset(
                    x: (unit + HubGeometry.gap) * CGFloat(tile.column),
                    y: (unit + HubGeometry.gap) * CGFloat(tile.row)
                )
            }

            addWidgetTile(state, unit: unit)
                .offset(
                    x: (unit + HubGeometry.gap) * CGFloat(addPosition.column),
                    y: (unit + HubGeometry.gap) * CGFloat(addPosition.row)
                )
        }
        .frame(
            maxWidth: .infinity,
            minHeight: HubGeometry.span(unit, max(state.canvas.rows, addPosition.row + 1)),
            alignment: .topLeading
        )
    }

    private func addWidgetTile(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        Button {
            model.controller.openGallery()
        } label: {
            VStack(spacing: 10) {
                // The disc sits where a contact portrait would, so the placeholder lines up with
                // its neighbours instead of floating.
                ZStack {
                    Circle().fill(Color(uiColor: .tertiarySystemFill))
                    Image(systemName: "plus").font(.title3.weight(.semibold))
                }
                .frame(width: 46, height: 46)
                .accessibilityHidden(true)
                Text(state.text.addWidget)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(.secondary)
            .padding(12)
            .frame(width: unit, height: unit)
            .background(
                Color(uiColor: .secondarySystemGroupedBackground).opacity(0.5),
                in: RoundedRectangle(cornerRadius: 24)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 24)
                    .strokeBorder(
                        Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            }
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
    }

    private func emptyCanvas(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)
            Text(state.text.emptyTitle)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(state.text.emptyBody)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
            addWidgetTile(state, unit: unit)
                .padding(.top, 14)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func widgetActions(_ tile: NativeHubTile, index: Int, state: NativePaymentHubSnapshot)
        -> some View
    {
        Button(state.text.editWidget, systemImage: "slider.horizontal.3") {
            model.controller.editWidget(id: tile.id)
        }
        Button(state.text.editHub, systemImage: "square.grid.2x2") {
            model.controller.setArranging(value: true)
        }
        if tile.sizes.count > 1 {
            Menu(state.text.chooseLayout) {
                ForEach(tile.sizes, id: \.id) { size in
                    Button {
                        model.controller.resizeWidget(id: tile.id, variantId: size.id)
                    } label: {
                        if size.id == tile.variantId {
                            Label(size.title, systemImage: "checkmark")
                        } else {
                            Text(size.title)
                        }
                    }
                }
            }
        }
        Button(state.text.moveUp, systemImage: "arrow.up") {
            model.controller.moveWidgetBy(id: tile.id, offset: -1)
        }
        .disabled(index == 0)
        Button(state.text.moveDown, systemImage: "arrow.down") {
            model.controller.moveWidgetBy(id: tile.id, offset: 1)
        }
        .disabled(index == state.canvas.tiles.count - 1)
        Divider()
        Button(state.text.remove, systemImage: "minus.circle", role: .destructive) {
            model.controller.removeWidget(id: tile.id)
        }
    }

    private func addWidgetPosition(_ canvas: NativeHubCanvas) -> (column: Int32, row: Int32) {
        // Append on the final occupied row when it has room, otherwise begin the next row.
        let lastRow = max(0, canvas.rows - 1)
        for column in 0..<max(1, canvas.columns) {
            let occupied = canvas.tiles.contains { tile in
                column >= tile.column && column < tile.column + tile.columns && lastRow >= tile.row
                    && lastRow < tile.row + tile.rows
            }
            if !occupied { return (column, lastRow) }
        }
        return (0, canvas.rows)
    }
}

private struct HubEditorSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @State private var providerLink = ""

    var body: some View {
        NavigationStack(path: navigationPath) {
            Group {
                if let state = model.snapshot {
                    gallery(state)
                        .navigationDestination(for: HubRoute.self) { route in
                            destination(route)
                        }
                } else {
                    ProgressView()
                }
            }
        }
    }

    private var routes: [HubRoute] {
        guard let state = model.snapshot else { return [] }
        switch state.screen {
        case "variants": return [.variants]
        case "configure": return [.variants, .configure]
        case "providersetup": return [.providerSetup]
        default: return []
        }
    }

    private var navigationPath: Binding<[HubRoute]> {
        Binding(
            get: { routes },
            set: { next in
                guard model.snapshot?.busy != true else { return }
                for _ in 0..<max(0, routes.count - next.count) {
                    model.controller.back()
                }
            }
        )
    }

    @ViewBuilder
    private func destination(_ route: HubRoute) -> some View {
        if let state = model.snapshot {
            switch route {
            case .variants:
                if let definition = state.selectedDefinition {
                    VariantGallery(state: state, definition: definition, controller: model.controller)
                } else {
                    unavailable(state)
                }
            case .providerSetup:
                if let setup = state.providerSetup {
                    ProviderSetupView(
                        controller: model.controller,
                        setup: setup,
                        dismissTitle: state.text.cancel
                    )
                } else {
                    unavailable(state)
                }
            case .configure:
                if let editor = state.editor, let definition = state.selectedDefinition {
                    WidgetConfigurationView(
                        model: model,
                        editor: editor,
                        definition: definition
                    )
                } else {
                    unavailable(state)
                }
            }
        }
    }

    private func unavailable(_ state: NativePaymentHubSnapshot) -> some View {
        ContentUnavailableView(state.text.unavailable, systemImage: "square.dashed")
            .toolbar { dismissButton(state.text.cancel) }
    }

    private func gallery(_ state: NativePaymentHubSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(state.text.galleryBody)
                    .font(.title3.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                builtInSection(state, title: state.text.sectionPeople, kinds: ["contacts", "shortcut"])
                // Collections (favorites, recents) are hidden while the provider widgets' UX is
                // settled; they keep working on existing Hubs. Restore with:
                // builtInSection(state, title: state.text.sectionCollections, kinds: ["favorites", "recents"])
                ForEach(state.catalog, id: \.title) { section in
                    VStack(alignment: .leading, spacing: 12) {
                        Text(section.title).font(.title3.weight(.bold))
                        LazyVGrid(columns: galleryColumns, spacing: 20) {
                            ForEach(section.entries, id: \.url) { entry in
                                galleryCell(title: entry.title, detail: entry.subtitle) {
                                    model.controller.startProviderSetup(link: entry.url)
                                } preview: {
                                    if let preview = entry.preview {
                                        ProviderTileFace(provider: preview, interactive: false, pay: { _ in })
                                            .allowsHitTesting(false)
                                    } else {
                                        RoundedRectangle(cornerRadius: 24)
                                            .fill(Color(uiColor: .secondarySystemGroupedBackground))
                                            .overlay { if entry.loading { ProgressView() } }
                                    }
                                }
                            }
                        }
                        if section.endsWithLink { addByLink(state) }
                    }
                }
                if !state.catalog.contains(where: { $0.endsWithLink }) { addByLink(state) }
            }
            .padding(20)
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(state.text.galleryTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { dismissButton(state.text.cancel) }
    }

    private var galleryColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]
    }

    @ViewBuilder
    private func builtInSection(_ state: NativePaymentHubSnapshot, title: String, kinds: [String]) -> some View {
        let definitions = state.gallery.filter { kinds.contains($0.kind) }
        if !definitions.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.title3.weight(.bold))
                LazyVGrid(columns: galleryColumns, spacing: 20) {
                    ForEach(definitions, id: \.id) { definition in
                        galleryCell(title: definition.title, detail: definition.detail) {
                            model.controller.selectDefinition(id: definition.id)
                        } preview: {
                            if let variant = definition.variants.first {
                                WidgetContent(tile: variant.preview, copy: state.text, interactive: false, pay: { _ in })
                                    .allowsHitTesting(false)
                            }
                        }
                    }
                }
            }
        }
    }

    private func galleryCell<Preview: View>(
        title: String,
        detail: String?,
        action: @escaping () -> Void,
        @ViewBuilder preview: () -> Preview
    ) -> some View {
        // A tap anywhere on the cell, preview included, chooses the widget. The preview is only a
        // picture, so it takes no touches of its own.
        VStack(alignment: .leading, spacing: 6) {
            preview().frame(height: 150).allowsHitTesting(false)
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
            if let detail {
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }

    private func addByLink(_ state: NativePaymentHubSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(state.text.addByLink, systemImage: "link")
                .font(.headline)
            Text(state.text.addByLinkBody)
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                TextField("https://", text: $providerLink)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit(openProviderLink)
                Button(state.text.paste) {
                    if let text = UIPasteboard.general.string { providerLink = text }
                }
                .buttonStyle(.bordered)
            }
            Button(state.text.continueTitle, action: openProviderLink)
                .buttonStyle(.borderedProminent)
                .disabled(providerLink.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
    }

    private func openProviderLink() {
        let link = providerLink.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !link.isEmpty else { return }
        model.controller.startProviderSetup(link: link)
    }

    @ToolbarContentBuilder
    private func dismissButton(_ label: String) -> some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.controller.close() } label: { Image(systemName: "xmark") }
                .accessibilityLabel(label)
                .disabled(model.snapshot?.busy == true)
        }
    }
}

private struct GalleryIllustration: View {
    let kind: String
    let symbol: String

    var body: some View {
        Group {
            if kind == "contacts" {
                HStack(spacing: -10) {
                    HubAvatar(initials: "", size: 54, seed: 0)
                    HubAvatar(initials: "", size: 64, seed: 1)
                    HubAvatar(initials: "", size: 54, seed: 2)
                }
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 42, weight: .medium))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 82, height: 82)
                    .background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 24))
            }
        }
        .accessibilityHidden(true)
    }
}

private struct VariantGallery: View {
    let state: NativePaymentHubSnapshot
    let definition: NativeHubDefinition
    let controller: NativePaymentHubController

    private var selected: NativeHubVariant? {
        definition.variants.first { $0.id == state.editor?.variantId }
    }

    /// Every page reserves the tallest layout, so the previews keep their relative sizes and paging
    /// never resizes the screen around them.
    private var previewRows: CGFloat {
        CGFloat(definition.variants.map { $0.rows }.max() ?? 1)
    }

    var body: some View {
        GeometryReader { proxy in
            let gaps = HubGeometry.gap * (previewRows - 1)
            // Roughly half the screen for the preview, the rest for the copy and the page dots
            // around it. A share rather than a fixed reserve keeps both readable as text scales.
            let budget = max(proxy.size.height * 0.45, 160)
            let unit = min((min(proxy.size.width, 420) - 52) / 2, (budget - gaps) / previewRows, 160)
            let stage = unit * previewRows + gaps
            ScrollView {
                VStack(spacing: 14) {
                    Spacer(minLength: 0)
                    Text(definition.title)
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    Text(definition.detail)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    TabView(selection: Binding(
                        get: { state.editor?.variantId ?? definition.variants.first?.id ?? "" },
                        set: { controller.selectVariant(id: $0) }
                    )) {
                        ForEach(definition.variants, id: \.id) { variant in
                            WidgetContent(tile: variant.preview, copy: state.text, interactive: false, pay: { _ in })
                                .frame(
                                    width: HubGeometry.span(unit, variant.columns),
                                    height: HubGeometry.span(unit, variant.rows)
                                )
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .tag(variant.id)
                                .accessibilityLabel(variant.title)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: definition.variants.count > 1 ? .always : .never))
                    .indexViewStyle(.page(backgroundDisplayMode: .always))
                    .frame(height: stage + (definition.variants.count > 1 ? 44 : 0))
                    if let selected {
                        Text(selected.title).font(.headline)
                        Text(selected.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
        .safeAreaInset(edge: .bottom) {
            HubPrimaryButton(
                title: state.editor?.addsDirectly == true ? state.text.addWidget : state.text.continueTitle,
                busy: state.busy
            ) {
                controller.configureSelected()
            }
        }
        // The bar says what to do here; the body already names what is being configured.
        .navigationTitle(state.text.chooseLayout)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct WidgetConfigurationView: View {
    @ObservedObject var model: NativePaymentHubModel
    let editor: NativeHubEditor
    let definition: NativeHubDefinition
    @State private var showingContactPicker = false

    private var controller: NativePaymentHubController { model.controller }
    private var isPersonal: Bool { editor.kind == "contacts" || editor.kind == "shortcut" }

    var body: some View {
        Group {
            if let state = model.snapshot {
                Form {
                    if isPersonal {
                        contacts(state)
                    } else if editor.kind == "favorites" || editor.kind == "recents" {
                        Section {
                            Text(definition.detail)
                            Text(state.text.automaticHint).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if editor.kind == "shortcut" {
                        Section(state.text.amount) {
                            HStack {
                                TextField(
                                    state.text.amount,
                                    text: Binding(
                                        get: { editor.amount },
                                        set: { controller.updateAmount(value: $0) }
                                    )
                                )
                                .keyboardType(.decimalPad)
                                Picker(
                                    editor.currencyCode,
                                    selection: Binding(
                                        get: { editor.currencyCode },
                                        set: { controller.selectCurrency(code: $0) }
                                    )
                                ) {
                                    ForEach(editor.currencyCodes, id: \.self) { code in
                                        Text(code).tag(code)
                                    }
                                }
                                .labelsHidden()
                            }
                            if let hint = editor.fiatAmountHint {
                                Text(hint)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        // The note is the shortcut's label ("Rent"); there is no separate name.
                        Section {
                            TextField(
                                state.text.comment,
                                text: Binding(
                                    get: { editor.comment },
                                    set: { controller.updateComment(value: $0) }
                                ))
                        } footer: {
                            Text(state.text.commentHint)
                        }
                    }
                }
                .disabled(state.busy)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 0) {
                        if let message = state.error ?? editor.validationMessage {
                            Text(message)
                                .font(.footnote)
                                .foregroundStyle(state.error == nil ? Color.secondary : Color.red)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                        }
                        HubPrimaryButton(
                            title: editor.isEditing ? state.text.save : state.text.addWidget,
                            busy: state.busy,
                            action: controller.saveWidget
                        )
                        .disabled(!editor.canSave)
                    }
                    .background(Color(uiColor: .systemGroupedBackground))
                }
                .navigationTitle(editor.isEditing ? state.text.editWidget : definition.title)
                .navigationBarTitleDisplayMode(.inline)
                .navigationBarBackButtonHidden(state.busy)
                .sheet(isPresented: $showingContactPicker) {
                    HubContactPicker(model: model)
                        .presentationDetents([.large])
                        .interactiveDismissDisabled(model.snapshot?.busy == true)
                }
            }
        }
    }

    @ViewBuilder
    private func contacts(_ state: NativePaymentHubSnapshot) -> some View {
        Section {
            ForEach(editor.selectedContacts, id: \.id) { contact in
                Button {
                    showingContactPicker = true
                } label: {
                    HStack(spacing: 12) {
                        HubAvatar(initials: contact.initials, size: 38)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(contact.title).foregroundStyle(.primary)
                            Text(contact.address).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if editor.selectedContacts.count < Int(editor.capacity) {
                Button {
                    showingContactPicker = true
                } label: {
                    Label(editor.selectionTitle, systemImage: "person.crop.circle.badge.plus")
                }
            }
        } header: {
            Text(state.text.contactsTitle)
        } footer: {
            // "0 of 1 selected" only restates the single row above it.
            if editor.capacity > 1 {
                Text(editor.selectionCount)
            }
        }
    }
}

private struct HubContactPicker: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var showingNewContact = false
    @State private var pendingDeletion: NativeHubContact?

    var body: some View {
        NavigationStack {
            if let state = model.snapshot, let editor = state.editor {
                let selected = editor.selectedContacts.filter { contact in
                    let query = state.query.trimmingCharacters(in: .whitespacesAndNewlines)
                    return query.isEmpty || contact.title.localizedCaseInsensitiveContains(query)
                        || contact.address.localizedCaseInsensitiveContains(query)
                }
                List {
                    Section {
                        ForEach(selected, id: \.id) { contact in
                            contactRow(contact, selected: true, state: state, editor: editor)
                                .contextMenu {
                                    if let index = editor.selectedContacts.firstIndex(where: {
                                        $0.id == contact.id
                                    }) {
                                        Button(state.text.moveUp, systemImage: "arrow.up") {
                                            model.controller.moveContact(id: contact.id, offset: -1)
                                        }.disabled(index == 0)
                                        Button(state.text.moveDown, systemImage: "arrow.down") {
                                            model.controller.moveContact(id: contact.id, offset: 1)
                                        }.disabled(index == editor.selectedContacts.count - 1)
                                    }
                                }
                        }
                        ForEach(editor.availableContacts, id: \.id) { contact in
                            contactRow(contact, selected: false, state: state, editor: editor)
                        }
                        if selected.isEmpty && editor.availableContacts.isEmpty {
                            Text(state.contactsEmpty ? state.text.noContacts : state.text.noMatches)
                                .foregroundStyle(.secondary)
                        }
                    } header: {
                        // A single-choice list needs no tally; the checkmark already says it.
                        if editor.capacity > 1 {
                            Text(editor.selectionCount)
                        }
                    } footer: {
                        if editor.capacity > 1,
                            editor.selectedContacts.count >= Int(editor.capacity)
                        {
                            Text(editor.selectionLimit)
                        }
                    }
                    if let error = state.error, !showingNewContact {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                }
                .disabled(state.busy)
                .searchable(
                    text: Binding(
                        get: { state.query },
                        set: { model.controller.updateQuery(value: $0) }
                    ), placement: .navigationBarDrawer(displayMode: .always), prompt: Text(state.text.search)
                )
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .scrollDismissesKeyboard(.interactively)
                .navigationTitle(editor.selectionTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            showingNewContact = true
                        } label: {
                            Label(state.text.addContact, systemImage: "person.crop.circle.badge.plus")
                        }
                        .disabled(
                            state.busy
                                || (editor.capacity > 1 && editor.selectedContacts.count >= editor.capacity))
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(state.text.done) { dismiss() }.disabled(state.busy)
                    }
                }
                .sheet(isPresented: $showingNewContact) {
                    AddHubContactSheet(model: model)
                        .interactiveDismissDisabled(model.snapshot?.busy == true)
                }
                .alert(
                    state.text.deleteContactTitle,
                    isPresented: Binding(
                        get: { pendingDeletion != nil },
                        set: { if !$0 { pendingDeletion = nil } }
                    ), presenting: pendingDeletion
                ) { contact in
                    Button(state.text.deleteContact, role: .destructive) {
                        model.controller.deleteContact(id: contact.id)
                        pendingDeletion = nil
                    }
                    Button(state.text.cancel, role: .cancel) { pendingDeletion = nil }
                } message: { contact in
                    Text(contact.title + "\n" + contact.address + "\n\n" + state.text.deleteContactBody)
                }
            }
        }
        .onAppear { model.controller.updateQuery(value: "") }
        .onDisappear { model.controller.updateQuery(value: "") }
    }

    private func contactRow(
        _ contact: NativeHubContact, selected: Bool, state: NativePaymentHubSnapshot, editor: NativeHubEditor
    ) -> some View {
        let atCapacity = editor.capacity > 1 && editor.selectedContacts.count >= editor.capacity
        return Button {
            model.controller.toggleContact(id: contact.id)
            if editor.capacity == 1 { dismiss() }
        } label: {
            HStack(spacing: 12) {
                HubAvatar(initials: contact.initials, size: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text(contact.title).foregroundStyle(.primary).lineLimit(1)
                    Text(contact.address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .opacity(!selected && atCapacity ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .disabled(!selected && atCapacity)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                pendingDeletion = contact
            } label: {
                Label(state.text.deleteContact, systemImage: "trash")
            }
            .tint(.red)
        }
    }
}

private struct AddHubContactSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var address = ""
    @State private var attemptedSave = false
    @State private var initialSerial: Int32

    init(model: NativePaymentHubModel) {
        self.model = model
        _initialSerial = State(initialValue: model.snapshot?.contactSavedSerial ?? 0)
    }

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
                Form {
                    Section {
                        TextField(state.text.name, text: $title)
                            .textContentType(.name)
                        TextField(state.text.address, text: $address)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    if attemptedSave, let error = state.error {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                }
                .disabled(state.busy)
                .navigationTitle(state.text.addContact)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(state.text.cancel) { dismiss() }
                            .disabled(state.busy)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(state.text.saveContact) {
                            attemptedSave = true
                            model.controller.addContact(title: title, address: address)
                        }
                        .disabled(state.busy)
                    }
                }
                .onChange(of: state.contactSavedSerial) { _, value in
                    if value != initialSerial { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct HubPrimaryButton: View {
    let title: String
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if busy { ProgressView().tint(.white) }
                Text(title).fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity, minHeight: 36)
        }
        .buttonStyle(.borderedProminent)
        .disabled(busy)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }
}

private struct WidgetContent: View {
    let tile: NativeHubTile
    let copy: NativePaymentHubCopy
    let interactive: Bool
    let pay: (String) -> Void
    var payProvider: (String) -> Void = { _ in }
    var openProviderSheet: (String) -> Void = { _ in }

    var body: some View {
        if let provider = tile.provider {
            ProviderTileFace(
                provider: provider,
                interactive: interactive,
                pay: payProvider,
                openSheet: openProviderSheet
            )
        } else {
            peopleBody
        }
    }

    private var palette: TilePalette { .neutral }

    @ViewBuilder
    private var peopleBody: some View {
        if tile.people.isEmpty {
            Text(tile.emptyText)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(HubTileStyle.padding)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .tileSurface(palette)
        } else if tile.kind == "shortcut", let person = tile.people.first {
            shortcut(person)
        } else if tile.columns == 1, let person = tile.people.first {
            single(person)
        } else {
            group
        }
    }

    /// One person and one amount, laid out like a provider's one-tap tile: who in the corner,
    /// the amount as the hero, and what it is for underneath.
    private func shortcut(_ person: NativeHubPerson) -> some View {
        Button { if !person.actionId.isEmpty { pay(person.actionId) } } label: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    HubAvatar(initials: person.initials, size: HubTileStyle.glyph)
                    Text(person.title)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                    Spacer(minLength: 4)
                    TapBadge(symbol: "bolt.fill", palette: palette)
                }
                if let amount = person.amount {
                    Text(amount).font(HubTileStyle.hero(30)).lineLimit(1).minimumScaleFactor(0.5)
                        .padding(.top, 8)
                }
                Spacer(minLength: 0)
            }
            .padding(HubTileStyle.padding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .tileSurface(palette)
            .contentShape(RoundedRectangle(cornerRadius: HubTileStyle.corner, style: .continuous))
        }
        .buttonStyle(TilePressStyle())
        .disabled(!interactive)
        .accessibilityLabel(person.title + (person.amount.map { ", " + $0 } ?? ""))
    }

    /// A single contact is a face: large enough to find at a glance, the whole tile to tap.
    private func single(_ person: NativeHubPerson) -> some View {
        GeometryReader { proxy in
            Button { if !person.actionId.isEmpty { pay(person.actionId) } } label: {
                VStack(spacing: 8) {
                    HubAvatar(initials: person.initials, size: min(proxy.size.width, proxy.size.height) * 0.5)
                    Text(person.title).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                }
                .padding(HubTileStyle.padding)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .tileSurface(palette)
                .contentShape(RoundedRectangle(cornerRadius: HubTileStyle.corner, style: .continuous))
            }
            .buttonStyle(TilePressStyle())
            .disabled(!interactive)
            .accessibilityLabel(person.title)
        }
    }

    /// A few people in a row, or on a large tile a grid with what each was last paid.
    private var group: some View {
        GeometryReader { proxy in
            let columns = tile.rows >= 2 ? 3 : min(max(tile.people.count, 1), 4)
            let rows = tile.rows >= 2 ? 2 : 1
            let cellWidth = (proxy.size.width - HubTileStyle.padding * 2) / CGFloat(columns)
            let cellHeight = (proxy.size.height - HubTileStyle.padding * 2) / CGFloat(rows)
            let avatar = max(28, min(cellWidth - 14, cellHeight - (tile.rows >= 2 ? 44 : 30)))
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: columns),
                spacing: 0
            ) {
                ForEach(Array(tile.people.enumerated()), id: \.element.id) { index, person in
                    personButton(person, avatarSize: avatar, seed: index)
                        .frame(height: cellHeight)
                }
            }
            .padding(HubTileStyle.padding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .tileSurface(palette)
        }
        .accessibilityElement(children: .contain)
    }

    private func personButton(_ person: NativeHubPerson, avatarSize: CGFloat, seed: Int) -> some View {
        Button { if !person.actionId.isEmpty { pay(person.actionId) } } label: {
            VStack(spacing: 5) {
                HubAvatar(initials: person.initials, size: avatarSize, seed: seed)
                Text(person.title)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                // Large tiles add context: what was last paid, never what a tap pays.
                if tile.rows >= 2 {
                    Text(person.lastPaid ?? " ")
                        .font(HubTileStyle.hero(11).weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .accessibilityHidden(person.lastPaid == nil)
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(TilePressStyle())
        .allowsHitTesting(interactive)
        .accessibilityLabel(person.title + (person.lastPaid.map { ", " + $0 } ?? ""))
    }
}

private struct HubAvatar: View {
    let initials: String
    let size: CGFloat
    var seed: Int = 0

    private var color: Color {
        let colors: [Color] = [.blue, .purple, .pink, .teal, .indigo, .orange]
        let value = initials.unicodeScalars.reduce(seed) { $0 + Int($1.value) }
        return colors[value % colors.count]
    }

    var body: some View {
        ZStack {
            Circle().fill(color.gradient)
            if initials.isEmpty {
                Image(systemName: "person.fill").font(.system(size: size * 0.45, weight: .medium))
            } else {
                Text(initials).font(.system(size: size * 0.36, weight: .semibold, design: .rounded))
            }
        }
        .foregroundStyle(.white)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private extension View {
    /// Liquid Glass where available, so the button reads as floating over the tiles.
    @ViewBuilder
    func floatingButton() -> some View {
        if #available(iOS 26, *) {
            buttonStyle(.glass).buttonBorderShape(.circle)
        } else {
            buttonStyle(.bordered).buttonBorderShape(.circle)
        }
    }

    /// Tiles fade softly under the floating header instead of ending at a hard edge.
    @ViewBuilder
    func softTopEdge() -> some View {
        if #available(iOS 26, *) {
            scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            self
        }
    }
}
