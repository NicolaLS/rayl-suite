import Shared
import SwiftUI
import UIKit

private struct AppShellTabIsSelectedKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    /// Whether the tab hosting this screen is the visible one. The app shell writes it; a screen
    /// presented outside a tab bar keeps the default. Passing it through the environment keeps the
    /// hosting tab body identical across selection changes, so its state and hosted view
    /// controllers survive a tab switch.
    var appShellTabIsSelected: Bool {
        get { self[AppShellTabIsSelectedKey.self] }
        set { self[AppShellTabIsSelectedKey.self] = newValue }
    }
}

private struct RecentPaymentsButton: View {
    let entry: NativePaymentScanRecentEntry
    let newCount: Int
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var notificationPulse = 0

    var body: some View {
        Button(action: action) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 17, weight: .semibold))
                .symbolEffect(.bounce, value: notificationPulse)
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .background(.thinMaterial, in: Circle())
        .overlay(alignment: .topTrailing) {
            if newCount > 0 {
                Text(newCount.badgeLabel)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.red, in: Capsule())
                    .offset(x: 4, y: -4)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityLabel(entry.title)
        .accessibilityValue(newCount > 0 ? String(newCount) : "")
        .onAppear { if newCount > 0 && !reduceMotion { notificationPulse += 1 } }
        .onChange(of: newCount) { old, new in
            if new > old && !reduceMotion { notificationPulse += 1 }
        }
    }
}

private extension Int {
    var badgeLabel: String { self > 99 ? "99+" : String(self) }
}

@MainActor
private final class NativePaymentScanModel: ObservableObject {
    @Published private(set) var snapshot: NativePaymentScanSnapshot?
    @Published var message: String?
    @Published private(set) var recentSnapshot: NativePaymentRecentSnapshot?

    var recentTransactionId: String? { recentSnapshot?.selectedDetail?.id }

    let controller: NativePaymentScanController
    private var cancels: [() -> Void] = []
    private var messageDismissTask: Task<Void, Never>?

    init(
        controller: NativePaymentScanController,
        recentController: NativePaymentRecentController?
    ) {
        self.controller = controller
        cancels.append(controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        })
        if let recentController {
            cancels.append(recentController.observe { [weak self] snapshot in
                self?.recentSnapshot = snapshot
            })
        }
        cancels.append(controller.observeMessages { [weak self] message in
            guard let self else { return }
            messageDismissTask?.cancel()
            self.message = message
            messageDismissTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(3))
                guard !Task.isCancelled else { return }
                self?.message = nil
            }
        })
    }

    deinit {
        messageDismissTask?.cancel()
        cancels.forEach { $0() }
    }
}

private enum ScanRecentRoute: Hashable {
    case recent
    case transaction(String)
}

/// Native Scan tab. The bounded animated hero is drawn by SwiftUI Canvas.
struct NativePaymentScanView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.appShellTabIsSelected) private var isSelected
    @StateObject private var model: NativePaymentScanModel

    @State private var showsRecent = false
    @State private var seenRecentCount = 0
    @State private var isVisible = false

    private let recentController: NativePaymentRecentController?

    /// [recentController] is supplied only by a product that reaches Recent from Scan instead of
    /// from a tab of its own.
    init(
        controller: NativePaymentScanController,
        recentController: NativePaymentRecentController? = nil
    ) {
        _model = StateObject(wrappedValue: NativePaymentScanModel(
            controller: controller,
            recentController: recentController
        ))
        self.recentController = recentController
    }

    var body: some View {
        Group {
            if recentController != nil {
                NavigationStack(path: recentNavigationPath) {
                    scanRoot
                        .toolbar(.hidden, for: .navigationBar)
                        .navigationDestination(for: ScanRecentRoute.self) { route in
                            recentDestination(route)
                                .toolbar(.visible, for: .navigationBar)
                        }
                }
            } else {
                scanRoot
            }
        }
        .sheet(isPresented: sheetPresented) {
            if let sheet = model.snapshot?.sheet, sheet.kind != "manualAmount" {
                NativePaymentScanSheetView(sheet: sheet, controller: model.controller)
                    .presentationDragIndicator(.visible)
            }
        }
        .fullScreenCover(isPresented: manualAmountPresented) {
            if let sheet = model.snapshot?.sheet, sheet.kind == "manualAmount" {
                NativePaymentManualAmountView(sheet: sheet, controller: model.controller)
            }
        }
        .onAppear {
            isVisible = true
            updateActivity()
        }
        .onDisappear {
            isVisible = false
            updateActivity()
        }
        .onChange(of: model.recentTransactionId, initial: true) { _, id in
            if id != nil { showsRecent = true }
            updateActivity()
        }
        .onChange(of: showsRecent) { _, shown in
            if shown {
                seenRecentCount = Int(model.snapshot?.recent?.transactionCount ?? 0)
            } else {
                recentController?.closeDetail()
            }
            updateActivity()
        }
        .onChange(of: model.snapshot?.recent?.transactionCount) { _, count in
            guard let count else {
                showsRecent = false
                return
            }
            let total = Int(count)
            seenRecentCount = showsRecent ? total : min(seenRecentCount, total)
        }
        .onChange(of: scenePhase) { _, _ in
            updateActivity()
        }
        .onChange(of: isSelected) { _, _ in
            updateActivity()
        }
    }

    private var scanRoot: some View {
        Group {
            if let snapshot = model.snapshot {
                scanContent(snapshot)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color(uiColor: .systemBackground))
        .overlay(alignment: .topTrailing) {
            if let recent = model.snapshot?.recent, recentController != nil {
                RecentPaymentsButton(
                    entry: recent,
                    newCount: max(
                        Int(recent.newTransactionCount), Int(recent.transactionCount) - seenRecentCount)
                ) { showsRecent = true }
                .padding(.top, 12)
                .padding(.trailing, 20)
            }
        }
        .overlay(alignment: .top) {
            if let message = model.message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(Color(uiColor: .secondarySystemBackground))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.primary, in: Capsule())
                    .padding(.top, 12)
                    .padding(.horizontal, 20)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.snappy, value: model.message)
    }

    private var recentNavigationPath: Binding<[ScanRecentRoute]> {
        Binding(
            get: {
                guard showsRecent else { return [] }
                var path: [ScanRecentRoute] = [.recent]
                if let id = model.recentTransactionId { path.append(.transaction(id)) }
                return path
            },
            set: { path in
                guard let last = path.last else {
                    showsRecent = false
                    return
                }
                showsRecent = true
                switch last {
                case .recent:
                    if model.recentTransactionId != nil { recentController?.closeDetail() }
                case .transaction(let id):
                    if model.recentTransactionId != id {
                        recentController?.selectTransaction(id: id)
                    }
                }
            }
        )
    }

    @ViewBuilder
    private func recentDestination(_ route: ScanRecentRoute) -> some View {
        if let snapshot = model.recentSnapshot, let recentController {
            switch route {
            case .recent:
                NativePaymentRecentList(snapshot: snapshot, destination: ScanRecentRoute.transaction)
            case .transaction(let id):
                if let detail = snapshot.selectedDetail, detail.id == id {
                    NativePaymentRecentDetailView(detail: detail, controller: recentController)
                } else {
                    ProgressView()
                }
            }
        } else {
            ProgressView()
        }
    }

    private func scanContent(_ snapshot: NativePaymentScanSnapshot) -> some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                RaylHeroView(phase: snapshot.heroPhase)
                    .frame(height: proxy.size.height * 0.5)
                    .allowsHitTesting(false)

                NativePaymentScanContentView(
                    content: snapshot.content,
                    cameraPermission: snapshot.cameraPermission,
                    onDismissResult: model.controller.dismissResult
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background {
            if snapshot.content.tapToContinue != nil && snapshot.cameraPermission == nil
                && snapshot.sheet == nil
            {
                // Keep this behind content so the Recent button consumes its own taps.
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: model.controller.dismissResult)
                    .accessibilityHidden(true)
            }
        }
    }

    private var sheetPresented: Binding<Bool> {
        Binding(
            get: {
                guard let sheet = model.snapshot?.sheet else { return false }
                return sheet.kind != "manualAmount"
            },
            set: { presented in
                guard !presented, let kind = model.snapshot?.sheet?.kind,
                    kind != "manualAmount" else { return }
                dismissSheet(kind)
            }
        )
    }

    private var manualAmountPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot?.sheet?.kind == "manualAmount" },
            set: { presented in
                guard !presented, model.snapshot?.sheet?.kind == "manualAmount" else { return }
                model.controller.dismissManualAmount()
            }
        )
    }

    private func dismissSheet(_ kind: String) {
        switch kind {
        case "confirmation": model.controller.dismissConfirmation()
        case "saveTarget": model.controller.dismissSaveTarget()
        default: break
        }
    }

    private func updateActivity() {
        let active = isVisible && isSelected && scenePhase == .active
        recentController?.setActive(active: active && showsRecent)
        // Clear the previous selection before rearming the camera for another scan of that QR.
        model.controller.setActive(
            active: active && !showsRecent && model.recentTransactionId == nil
        )
    }
}

private struct NativePaymentScanContentView: View {
    let content: NativePaymentScanContent
    let cameraPermission: NativeCameraPermissionContent?
    let onDismissResult: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 12) {
            if let cameraPermission {
                cameraPermissionContent(cameraPermission)
            } else {
                paymentContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, 16)
        .padding(.horizontal, 24)
    }

    @ViewBuilder
    private var paymentContent: some View {
        VStack(spacing: 12) {
            paymentSummary
        }
        .allowsHitTesting(false)

        if let tapToContinue = content.tapToContinue {
            Button(tapToContinue, action: onDismissResult)
                .buttonStyle(.plain)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(minHeight: 44)
        }
    }

    @ViewBuilder
    private var paymentSummary: some View {
        if content.kind == "resolving" {
            ProgressView()
                .controlSize(.small)
        }

        if content.kind == "success", let amount = content.primaryAmount {
            (Text(content.title).foregroundStyle(paymentSuccessColor(colorScheme))
                + Text(" \(amount)"))
                .font(.largeTitle)
                .multilineTextAlignment(.center)
        } else {
            Text(content.title)
                .font(titleFont)
                .fontWeight(.semibold)
                .foregroundStyle(titleColor)
                .multilineTextAlignment(.center)
        }

        if let subtitle = content.subtitle {
            Text(subtitle)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, content.kind == "error" ? 20 : 0)
                .padding(.vertical, content.kind == "error" ? 16 : 0)
                .background {
                    if content.kind == "error" {
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(.red.opacity(0.4), lineWidth: 1)
                    }
                }
        }

        if let secondary = content.secondaryText {
            Text(secondary)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
        }
    }

    private func cameraPermissionContent(
        _ permission: NativeCameraPermissionContent
    ) -> some View {
        VStack(spacing: 12) {
            Text(permission.title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(permission.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let actionTitle = permission.openSettingsTitle {
                Button(actionTitle, action: openSystemSettings)
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private var titleFont: Font {
        switch content.kind {
        case "idle": return .title2
        case "success": return .largeTitle
        default: return .title3
        }
    }

    private var titleColor: Color {
        switch content.kind {
        case "error": return .red
        case "alreadyPaid", "receipt": return paymentSuccessColor(colorScheme)
        default: return .accentColor
        }
    }
}

private struct NativePaymentManualAmountView: View {
    let sheet: NativePaymentScanSheet
    let controller: NativePaymentScanController
    @State private var reviewingManualAmount = false

    var body: some View {
        NavigationStack {
            amountPage(reviewing: false)
                .navigationDestination(isPresented: $reviewingManualAmount) {
                    amountPage(reviewing: true)
                }
        }
        .accessibilityIdentifier("payment_manual_amount_screen")
        .onChange(of: sheet.amount) { _, _ in reviewingManualAmount = false }
        .onChange(of: sheet.currencyLabel) { _, _ in reviewingManualAmount = false }
        .onChange(of: sheet.canSubmit) { _, canSubmit in
            if !canSubmit { reviewingManualAmount = false }
        }
    }

    private func amountPage(reviewing: Bool) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                NativePaymentRecipientView(sheet: sheet)
                if let body = sheet.body {
                    Text(body)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                if reviewing {
                    if let amount = sheet.manualReview?.amount {
                        Text(amount)
                            .font(.largeTitle.weight(.semibold))
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("payment_manual_amount_review")
                    }
                } else {
                    manualAmountContent
                    NativePaymentAmountKeypad(
                        controller: controller, allowsDecimal: sheet.allowsDecimal)
                }
            }
            .padding(24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                guard sheet.canSubmit else { return }
                if reviewing {
                    controller.submitManualAmount()
                } else if sheet.manualReview != nil {
                    reviewingManualAmount = true
                }
            } label: {
                Text(
                    reviewing
                        ? sheet.manualReview?.payTitle ?? sheet.primaryActionTitle
                        : sheet.primaryActionTitle
                )
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 28)
                .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 16))
            .controlSize(.large)
            .disabled(!sheet.canSubmit)
            .accessibilityIdentifier(
                reviewing
                    ? "payment_manual_amount_pay_button"
                    : "payment_manual_amount_continue_button")
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
        }
        .navigationTitle(reviewing ? sheet.manualReview?.title ?? sheet.title : sheet.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let cancel = sheet.secondaryActionTitle {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(cancel, action: controller.dismissManualAmount)
                        .accessibilityIdentifier("payment_manual_amount_cancel_button")
                }
            }
        }
    }

    @ViewBuilder
    private var manualAmountContent: some View {
        if let amount = sheet.amount, let currency = sheet.currencyLabel {
            VStack(spacing: 8) {
                Text(amount)
                    .font(.system(size: 56, weight: .medium, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.35)
                    .frame(maxWidth: .infinity)
                Text(currency)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 16)
            .accessibilityElement(children: .combine)
        }

        if let currencySwitch = sheet.currencySwitch {
            Button {
                controller.manualAmountKey(key: "switchCurrency")
            } label: {
                Label(currencySwitch.title, systemImage: "arrow.up.arrow.down")
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("payment_manual_amount_switch_currency")

            HStack {
                Text(currencySwitch.currency)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(currencySwitch.equivalent ?? "—")
                    .font(.title3)
                    .monospacedDigit()
            }
            .padding()
            .background(Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("payment_manual_amount_equivalent")
        }

        if let rangeMessage = sheet.rangeMessage {
            Text(rangeMessage)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.red)
        }
    }
}

private struct NativePaymentScanSheetView: View {
    let sheet: NativePaymentScanSheet
    let controller: NativePaymentScanController
    @State private var contentHeight: CGFloat = 320

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(sheet.title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)

                if let body = sheet.body {
                    Text(body)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                NativePaymentRecipientView(sheet: sheet)

                switch sheet.kind {
                case "confirmation": confirmationContent
                case "saveTarget": saveTargetContent
                default: EmptyView()
                }

                actionButtons
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 32)
            .onGeometryChange(for: CGFloat.self) { proxy in
                ceil(proxy.size.height)
            } action: { height in
                contentHeight = max(1, height)
            }
        }
        // Measure the content, not the scroll viewport, so longer copy and Dynamic Type can
        // grow the sheet. The scroll view remains usable when the content exceeds the screen.
        .presentationDetents(
            sheet.kind == "confirmation" || sheet.kind == "repeatPayment"
                ? [.height(contentHeight)] : [.large]
        )
        .scrollBounceBehavior(.basedOnSize)
    }

    @ViewBuilder
    private var confirmationContent: some View {
        if let amount = sheet.amount {
            Text(amount)
                .font(.title3.weight(.bold))
        }
        if let exactAmount = sheet.exactAmount {
            Text(exactAmount)
                .font(.body)
                .foregroundStyle(.secondary)
        }
        if let fundingSource = sheet.fundingSource {
            Text(fundingSource)
                .font(.body)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var saveTargetContent: some View {
        if let label = sheet.textFieldLabel {
            TextField(
                label,
                text: Binding(
                    get: { sheet.textFieldValue ?? "" },
                    set: controller.updateSaveTargetTitle
                )
            )
            .textFieldStyle(.roundedBorder)
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        Button(action: primaryAction) {
            Text(sheet.primaryActionTitle)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!sheet.canSubmit)

        if let secondary = sheet.secondaryActionTitle {
            Button(action: secondaryAction) {
                Text(secondary)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
    }

    private func primaryAction() {
        switch sheet.kind {
        case "confirmation": controller.submitConfirmation()
        case "saveTarget": controller.saveTarget()
        default: break
        }
    }

    private func secondaryAction() {
        switch sheet.kind {
        case "confirmation": controller.dismissConfirmation()
        case "saveTarget": controller.dismissSaveTarget()
        default: break
        }
    }
}

private struct NativePaymentRecipientView: View {
    let sheet: NativePaymentScanSheet

    var body: some View {
        if let title = sheet.recipientTitle, let description = sheet.recipientDescription {
            HStack(spacing: 12) {
                if let encoded = sheet.recipientImageBase64,
                    let data = Data(base64Encoded: encoded),
                    let image = UIImage(data: data)
                {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 56, height: 56)
                        .clipped()
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    Text(description)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                Spacer(minLength: 0)
            }
        }
    }
}

private struct NativePaymentAmountKeypad: View {
    let controller: NativePaymentScanController
    let allowsDecimal: Bool

    private let rows = [
        ["1", "2", "3"],
        ["4", "5", "6"],
        ["7", "8", "9"],
        ["decimal", "0", "backspace"],
    ]

    var body: some View {
        VStack(spacing: 12) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 12) {
                    ForEach(row, id: \.self) { key in
                        Button {
                            controller.manualAmountKey(key: key)
                        } label: {
                            Group {
                                if key == "backspace" {
                                    Image(systemName: "delete.left")
                                } else {
                                    Text(key == "decimal" ? (Locale.current.decimalSeparator ?? ".") : key)
                                }
                            }
                            .font(.title.weight(.medium))
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(
                                Color(uiColor: .secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 16)
                            )
                            .contentShape(RoundedRectangle(cornerRadius: 16))
                        }
                        .buttonStyle(.plain)
                        .disabled(key == "decimal" && !allowsDecimal)
                        .opacity(key == "decimal" && !allowsDecimal ? 0 : 1)
                        .accessibilityHidden(key == "decimal" && !allowsDecimal)
                    }
                }
            }
        }
    }
}

@MainActor
private final class NativePaymentRecentModel: ObservableObject {
    @Published private(set) var snapshot: NativePaymentRecentSnapshot?

    let controller: NativePaymentRecentController
    private var cancel: (() -> Void)?

    init(controller: NativePaymentRecentController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

/// Native Recent tab. Successful transactions open their receipt directly.
struct NativePaymentRecentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.appShellTabIsSelected) private var isSelected
    @StateObject private var model: NativePaymentRecentModel

    init(controller: NativePaymentRecentController) {
        _model = StateObject(wrappedValue: NativePaymentRecentModel(controller: controller))
    }

    var body: some View {
        NavigationStack(path: navigationPath) {
            Group {
                if let snapshot = model.snapshot {
                    NativePaymentRecentList(snapshot: snapshot, destination: { $0 })
                        .navigationDestination(for: String.self) { id in
                            if let detail = snapshot.selectedDetail, detail.id == id {
                                NativePaymentRecentDetailView(
                                    detail: detail,
                                    controller: model.controller
                                )
                            } else {
                                ProgressView()
                            }
                        }
                } else {
                    ProgressView()
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .onAppear(perform: updateActivity)
        .onDisappear {
            model.controller.setActive(active: false)
        }
        .onChange(of: scenePhase) { _, _ in
            updateActivity()
        }
        .onChange(of: isSelected) { _, _ in
            updateActivity()
        }
    }

    private var navigationPath: Binding<[String]> {
        Binding(
            get: {
                model.snapshot?.selectedDetail.map { [$0.id] } ?? []
            },
            set: { path in
                if let id = path.last {
                    model.controller.selectTransaction(id: id)
                } else if model.snapshot?.selectedDetail != nil {
                    model.controller.closeDetail()
                }
            }
        )
    }

    private func updateActivity() {
        model.controller.setActive(active: isSelected && scenePhase == .active)
    }
}

/// The same native list participates in Scan's navigation stack or a dedicated Recent tab.
private struct NativePaymentRecentList<Destination: Hashable>: View {
    let snapshot: NativePaymentRecentSnapshot
    let destination: (String) -> Destination

    var body: some View {
        Group {
            if snapshot.items.isEmpty {
                ContentUnavailableView(
                    snapshot.emptyMessage,
                    systemImage: "clock"
                )
            } else {
                List(snapshot.items, id: \.id) { item in
                    NavigationLink(value: destination(item.id)) {
                        NativePaymentRecentRow(item: item)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle(snapshot.title)
        .navigationBarTitleDisplayMode(.large)
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

private struct NativePaymentRecentRow: View {
    let item: NativePaymentRecentItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(toneColor)
                .frame(width: 38, height: 38)
                .background(toneColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(item.amount)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                    Spacer()
                    Text(Date(timeIntervalSince1970: Double(item.createdAtMs) / 1_000), style: .time)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(item.statusLabel)
                    .font(.subheadline)
                    .foregroundStyle(toneColor)
                    .lineLimit(2)
                if let supporting = item.supportingText {
                    Text(supporting)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 3)
    }

    private var symbol: String {
        item.statusTone == "success" ? "checkmark.circle.fill" : "exclamationmark.circle.fill"
    }

    private var toneColor: Color {
        item.statusTone == "success" ? .green : .red
    }
}

private struct NativePaymentRecentDetailView: View {
    let detail: NativePaymentRecentDetail
    let controller: NativePaymentRecentController

    var body: some View {
        Group {
            if let receipt = detail.receipt {
                NativePaymentReceiptView(receipt: receipt)
            } else {
                GeometryReader { proxy in
                    VStack(spacing: 0) {
                        RaylHeroView(phase: detail.heroPhase)
                            .frame(height: proxy.size.height * 0.48)

                        NativePaymentRecentResultContent(
                            content: detail.content,
                            onDone: controller.closeDetail
                        )
                    }
                }
            }
        }
        .background(Color(uiColor: .systemBackground))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct NativePaymentReceiptView: View {
    let receipt: NativePaymentReceiptContent
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 16) {
                    receiptRow(receipt.amountLabel, value: receipt.amount)
                    VStack(alignment: .leading, spacing: 8) {
                        receiptRow(receipt.feeLabel, value: receipt.fee)
                        if let hint = receipt.feeHint {
                            Label(hint, systemImage: "info.circle")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if let recipient = receipt.recipient {
                        receiptRow(receipt.recipientLabel, value: recipient)
                    }
                    if let domain = receipt.domain {
                        receiptRow(receipt.domainLabel, value: domain)
                    }
                    receiptRow(receipt.timestampLabel, value: receipt.timestamp)
                }

                if let preimage = receipt.preimage {
                    Divider()
                    VStack(spacing: 16) {
                        Text(receipt.preimageLabel)
                            .font(.headline)

                        Button { copyPreimage(preimage) } label: {
                            NativePaymentPreimageQrCode(data: preimage)
                                .aspectRatio(1, contentMode: .fit)
                                .frame(maxWidth: 280)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(receipt.copyTitle)
                        .accessibilityIdentifier("payment_receipt_qr")

                        Text(preimage)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("payment_receipt_preimage")

                        Button { copyPreimage(preimage) } label: {
                            Label(
                                copied ? receipt.copiedTitle : receipt.copyTitle,
                                systemImage: copied ? "checkmark" : "doc.on.doc"
                            )
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("payment_receipt_copy")

                        Text(receipt.explanation)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: 560)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .navigationTitle(receipt.title)
        .onChange(of: receipt.preimage) { _, _ in copied = false }
    }

    private func receiptRow(_ label: String, value: String) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                Text(label)
                    .foregroundStyle(.secondary)
                    .fixedSize()
                Spacer(minLength: 0)
                Text(value)
                    .fontWeight(.medium)
                    .fixedSize()
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .foregroundStyle(.secondary)
                Text(value)
                    .fontWeight(.medium)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func copyPreimage(_ preimage: String) {
        UIPasteboard.general.string = preimage
        copied = true
    }
}

private struct NativePaymentRecentResultContent: View {
    let content: NativePaymentScanContent
    let onDone: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if content.kind == "success", let amount = content.primaryAmount {
                    (Text(content.title).foregroundStyle(paymentSuccessColor(colorScheme))
                        + Text(" \(amount)"))
                        .font(.title)
                        .multilineTextAlignment(.center)
                } else {
                    Text(content.title)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(
                            content.kind == "error" ? .red : paymentSuccessColor(colorScheme)
                        )
                        .multilineTextAlignment(.center)
                }

                if let subtitle = content.subtitle {
                    Text(subtitle)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                if let supporting = content.secondaryText {
                    Text(supporting)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                }
                if let done = content.tapToContinue {
                    Button(done, action: onDone)
                        .buttonStyle(.plain)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }
}

private func paymentSuccessColor(_ colorScheme: ColorScheme) -> Color {
    let palette = NativeHeroPaletteKt.nativeHeroPalette(phaseValue: "succeeded")
    return HeroTint(argb: colorScheme == .dark ? palette.darkArgb : palette.lightArgb).color
}
