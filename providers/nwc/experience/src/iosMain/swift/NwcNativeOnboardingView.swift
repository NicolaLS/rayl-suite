import AVFoundation
import Shared
import SwiftUI
import UIKit

@MainActor
private final class NwcNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: NwcNativeOnboardingSnapshot?

    let controller: NwcNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: NwcNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct NwcNativeOnboardingView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model: NwcNativeOnboardingModel

    init(controller: NwcNativeOnboardingController) {
        _model = StateObject(wrappedValue: NwcNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .toolbar {
                            if snapshot.settingsFlow || (!snapshot.connectionOnly && snapshot.step != "welcome") {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button(
                                        action: snapshot.settingsFlow
                                            ? model.controller.finishSettingsWalletFlow
                                            : model.controller.back
                                    ) {
                                        Label(
                                            snapshot.settingsFlow ? snapshot.cancelTitle : snapshot.backTitle,
                                            systemImage: snapshot.settingsFlow ? "xmark" : "chevron.left"
                                        )
                                            .labelStyle(.iconOnly)
                                    }
                                }
                            }
                        }
                        .sheet(isPresented: confirmationPresented(snapshot)) {
                            confirmation(snapshot)
                                .interactiveDismissDisabled(snapshot.saving)
                        }
                } else {
                    ProgressView()
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            updateScannerActivity(phase: phase)
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        switch snapshot.step {
        case "welcome":
            NativeOnboardingWelcomeView(
                title: snapshot.welcomeTitle,
                subtitle: snapshot.welcomeSubtitle,
                description: snapshot.welcomeDescription,
                actionTitle: snapshot.getStartedTitle,
                action: model.controller.continueWelcome
            )
        case "features":
            NativeOnboardingFeaturesView(
                pages: featurePages(snapshot),
                currentPage: snapshot.featurePage,
                stepIndex: snapshot.stepIndex,
                stepCount: snapshot.stepCount,
                actionTitle: snapshot.featuresNextTitle,
                onPageChanged: model.controller.setFeaturePage,
                action: requestCameraPermissionThenContinue
            )
        case "autoPay":
            NativeOnboardingAutoPayView(
                title: snapshot.autoPayTitle,
                body: snapshot.autoPayBody,
                alwaysTitle: snapshot.autoPayAlwaysTitle,
                thresholdTitle: snapshot.autoPayThresholdTitle,
                thresholdLabel: snapshot.autoPayThresholdLabel,
                hint: snapshot.autoPayHint,
                actionTitle: snapshot.autoPayNextTitle,
                confirmationMode: snapshot.confirmationMode,
                thresholdIndex: snapshot.thresholdIndex,
                thresholdStepCount: snapshot.thresholdStepCount,
                stepIndex: snapshot.stepIndex,
                stepCount: snapshot.stepCount,
                onConfirmationModeChanged: model.controller.setConfirmationMode,
                onThresholdIndexChanged: model.controller.setThresholdIndex,
                action: model.controller.continueAutoPay
            )
        case "agreement":
            NativeOnboardingAgreementView(
                title: snapshot.agreementTitle,
                body: snapshot.agreementBody,
                checkboxTitle: snapshot.agreementCheckboxTitle,
                actionTitle: snapshot.agreementNextTitle,
                hasAgreed: snapshot.hasAgreed,
                stepIndex: snapshot.stepIndex,
                stepCount: snapshot.stepCount,
                onAgreementChanged: model.controller.setAgreement,
                action: model.controller.continueAgreement
            )
        case "instructions":
            instructions(snapshot)
        case "wallet":
            wallet(snapshot)
        default:
            ProgressView()
        }
    }

    private func instructions(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        NativeOnboardingProgressLayout(
            stepIndex: snapshot.stepIndex,
            stepCount: snapshot.stepCount
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(snapshot.instructionsTitle)
                        .font(.title2.bold())
                    Text(snapshot.instructionsIntro)
                        .foregroundStyle(.secondary)

                    ForEach(Array(snapshot.instructionSteps.enumerated()), id: \.offset) {
                        index,
                        step in
                        HStack(alignment: .top, spacing: 12) {
                            Text(String(index + 1))
                                .font(.headline)
                                .foregroundStyle(.white)
                                .frame(width: 32, height: 32)
                                .background(Color.accentColor, in: Circle())
                            Text(step)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button(snapshot.connectWalletTitle, action: model.controller.showWalletConnection)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
        }
    }

    private func wallet(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(snapshot.addDescription)
                    .foregroundStyle(.secondary)

                SecureField(
                    snapshot.uriPlaceholder,
                    text: Binding(
                        get: { snapshot.uri },
                        set: model.controller.updateUri
                    ),
                    prompt: Text(snapshot.uriLabel)
                )
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit(model.controller.submitUri)
                .privacySensitive()
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))

                Button(snapshot.pasteTitle) {
                    model.controller.pasteUri()
                }

                if let error = snapshot.uriError {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityAddTraits(.isStaticText)
                }

                Button(snapshot.connectWalletTitle, action: model.controller.submitUri)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!snapshot.canSubmitUri)

                scannerCard(snapshot)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
        .navigationTitle(snapshot.addTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.controller.setScannerActive(active: scenePhase == .active)
        }
        .onDisappear {
            model.controller.setScannerActive(active: false)
        }
    }

    private func scannerCard(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        VStack(spacing: 14) {
            Image(systemName: snapshot.cameraAuthorization == "authorized" ? "viewfinder" : "camera.fill")
                .font(.system(size: 48, weight: .light))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)

            Text(
                snapshot.cameraAuthorization == "authorized" && !snapshot.scannerUnavailable
                    ? snapshot.scanInstruction
                    : cameraMessage(snapshot)
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

            if snapshot.cameraAuthorization == "notDetermined" {
                Button(snapshot.scanAllowCamera, action: model.controller.requestCameraAccess)
                    .buttonStyle(.bordered)
            } else if snapshot.cameraAuthorization == "denied" {
                Button(snapshot.scanOpenSettings, action: openSystemSettings)
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func confirmation(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(snapshot.confirmDescription)
                        .foregroundStyle(.secondary)

                    if snapshot.discoveryLoading {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(snapshot.discoveryLoadingTitle)
                        }
                    }

                    if !snapshot.warnings.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(snapshot.warningHeading)
                                .font(.headline)
                            ForEach(Array(snapshot.warnings.enumerated()), id: \.offset) {
                                _,
                                warning in
                                Text(warning)
                                    .font(.subheadline)
                            }
                        }
                        .foregroundStyle(.orange)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    }

                    if snapshot.walletPublicKey != nil {
                        TextField(
                            snapshot.aliasLabel,
                            text: Binding(
                                get: { snapshot.alias },
                                set: model.controller.updateAlias
                            )
                        )
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(snapshot.saving)
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))

                        detail(snapshot.publicKeyLabel, snapshot.walletPublicKey)
                        detail(snapshot.relayLabel, snapshot.relay)
                        detail(snapshot.lightningAddressLabel, snapshot.lightningAddress)
                        detail(snapshot.methodsLabel, snapshot.methods)
                        detail(snapshot.encryptionLabel, snapshot.encryptionSchemes)

                        if let activeEncryption = snapshot.activeEncryption {
                            Text(activeEncryption)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let error = snapshot.connectionError {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityAddTraits(.isStaticText)

                        if !snapshot.discoveryLoading && snapshot.walletPublicKey == nil {
                            Button(snapshot.retryTitle, action: model.controller.retryDiscovery)
                                .buttonStyle(.bordered)
                        }
                    }

                    Button(action: model.controller.confirmConnection) {
                        HStack {
                            if snapshot.saving {
                                ProgressView()
                            }
                            Text(snapshot.confirmActionTitle)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!snapshot.canConfirm)
                }
                .padding(24)
            }
            .navigationTitle(snapshot.confirmTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(snapshot.cancelTitle, action: model.controller.cancelConnection)
                        .disabled(snapshot.saving)
                }
            }
        }
    }

    @ViewBuilder
    private func detail(_ title: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.body)
                    .textSelection(.enabled)
            }
        }
    }

    private func featurePages(
        _ snapshot: NwcNativeOnboardingSnapshot
    ) -> [NativeOnboardingPageValue] {
        snapshot.featurePages.enumerated().map { index, page in
            NativeOnboardingPageValue(
                id: index,
                title: page.title,
                subtitle: page.subtitle,
                body: page.body
            )
        }
    }

    private func confirmationPresented(
        _ snapshot: NwcNativeOnboardingSnapshot
    ) -> Binding<Bool> {
        Binding(
            get: { snapshot.confirmationPresented },
            set: { presented in
                if !presented {
                    model.controller.cancelConnection()
                }
            }
        )
    }

    private func requestCameraPermissionThenContinue() {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined else {
            model.controller.continueFeatures()
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { _ in
            DispatchQueue.main.async {
                model.controller.continueFeatures()
            }
        }
    }

    private func cameraMessage(_ snapshot: NwcNativeOnboardingSnapshot) -> String {
        if snapshot.scannerUnavailable {
            return snapshot.scanUnavailable
        }
        switch snapshot.cameraAuthorization {
        case "restricted", "unavailable": return snapshot.scanRestricted
        default: return snapshot.scanPermission
        }
    }

    private func openSystemSettings() {
        guard let settings = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(settings)
    }

    private func updateScannerActivity(phase: ScenePhase) {
        guard model.snapshot?.step == "wallet" else { return }
        model.controller.setScannerActive(active: phase == .active)
    }
}
