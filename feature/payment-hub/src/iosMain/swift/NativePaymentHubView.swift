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
}

private enum HubGeometry {
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
    }

    private var editorPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot.map { $0.screen != "hub" } ?? false },
            set: { if !$0 { model.controller.close() } }
        )
    }

    private func canvas(_ state: NativePaymentHubSnapshot) -> some View {
        VStack(spacing: 0) {
            if let error = state.error, state.screen == "hub" {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, HubGeometry.gutter)
                    .padding(.bottom, 12)
            }

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
                .onAppear { model.controller.setCanvasColumns(value: Int32(columns)) }
                .onChange(of: columns) { _, value in
                    model.controller.setCanvasColumns(value: Int32(value))
                }
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
                    interactive: true,
                    pay: { model.controller.pay(actionId: $0) }
                )
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
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 16)], spacing: 24) {
                    ForEach(state.gallery, id: \.id) { definition in
                        Button {
                            model.controller.selectDefinition(id: definition.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                GalleryIllustration(kind: definition.kind, symbol: definition.symbol)
                                    .frame(height: 126)
                                    .frame(maxWidth: .infinity)
                                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
                                Text(definition.title)
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                                Text(definition.detail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(3)
                                    .multilineTextAlignment(.leading)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                }
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
                            WidgetContent(tile: variant.preview, interactive: false, pay: { _ in })
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
            HubPrimaryButton(title: state.text.continueTitle, busy: false) {
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
                    Section {
                        TextField(
                            state.text.widgetName,
                            text: Binding(
                                get: { editor.title },
                                set: { controller.updateTitle(value: $0) }
                            ))
                    }
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
                            TextField(
                                state.text.comment,
                                text: Binding(
                                    get: { editor.comment },
                                    set: { controller.updateComment(value: $0) }
                                ))
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

private struct HubPrimaryButton: View {
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
    let interactive: Bool
    let pay: (String) -> Void

    var body: some View {
        GeometryReader { proxy in
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 5) {
                    if tile.kind == "shortcut" { Image(systemName: "bolt.fill") }
                    Text(tile.title).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                if tile.people.isEmpty {
                    Text(tile.emptyText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                        .multilineTextAlignment(.center)
                } else {
                    people(size: proxy.size)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func people(size: CGSize) -> some View {
        let columns = tile.columns == 1 ? 1 : (tile.rows >= 2 ? 3 : 4)
        // A contact and a shortcut often sit side by side, so the portrait always budgets for an
        // amount line. Sizing it from the content gave neighbouring tiles different circles.
        let singleHeightAllowance: CGFloat = 108
        let avatarSize = min(
            tile.columns == 1 ? 66 : (tile.rows >= 2 ? 72 : 50),
            (size.width - 40) / CGFloat(columns) - 10,
            tile.columns == 1 ? size.height - singleHeightAllowance : size.height
        )
        if tile.columns == 1 {
            if let person = tile.people.first {
                personButton(person, avatarSize: max(32, avatarSize))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns),
                spacing: tile.rows >= 2 ? 20 : 8
            ) {
                ForEach(Array(tile.people.enumerated()), id: \.element.id) { index, person in
                    personButton(person, avatarSize: max(28, avatarSize), seed: index)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }

    /// A single face always budgets the line so neighbouring tiles line up. A multi-contact tile
    /// only needs its own cells to agree, so one with no amounts at all keeps the space.
    private var reservesAmount: Bool {
        tile.columns == 1 || tile.people.contains { $0.amount != nil }
    }

    private func personButton(_ person: NativeHubPerson, avatarSize: CGFloat, seed: Int = 0) -> some View {
        Button {
            if !person.actionId.isEmpty { pay(person.actionId) }
        } label: {
            VStack(spacing: 6) {
                HubAvatar(initials: person.initials, size: avatarSize, seed: seed)
                Text(person.title)
                    .font(tile.columns == 1 ? .subheadline.weight(.semibold) : .caption.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                // The line is kept even without an amount so that portraits and names sit at the
                // same height across neighbouring tiles.
                if reservesAmount {
                    Text(person.amount ?? " ")
                        .font(tile.columns == 1 ? .subheadline.weight(.semibold) : .caption2)
                        .foregroundStyle(Color.accentColor)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                        .opacity(person.amount == nil ? 0 : 1)
                        .accessibilityHidden(person.amount == nil)
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .allowsHitTesting(interactive)
        .accessibilityLabel(person.title + (person.amount.map { ", " + $0 } ?? ""))
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
