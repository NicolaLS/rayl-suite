import AVFoundation
import Shared
import SwiftUI

@MainActor
private final class FlintNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: FlintNativeOnboardingSnapshot?

    let controller: FlintNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: FlintNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct FlintNativeOnboardingView: View {
    @StateObject private var model: FlintNativeOnboardingModel

    init(controller: FlintNativeOnboardingController) {
        _model = StateObject(wrappedValue: FlintNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .toolbar {
                            if snapshot.canGoBack {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button(action: model.controller.back) {
                                        Label(snapshot.backTitle, systemImage: "chevron.left")
                                            .labelStyle(.iconOnly)
                                    }
                                }
                            }
                        }
                        .alert(
                            snapshot.removalTitle,
                            isPresented: removalPresented(snapshot)
                        ) {
                            Button(snapshot.cancelTitle, role: .cancel) {
                                model.controller.cancelReset()
                            }
                            Button(snapshot.removalConfirmTitle, role: .destructive) {
                                model.controller.confirmReset()
                            }
                        } message: {
                            Text(snapshot.removalBody)
                        }
                } else {
                    ProgressView()
                }
            }
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
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

    private func instructions(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
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

    private func wallet(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                switch snapshot.walletKind {
                case "import":
                    importWallet(snapshot)
                case "recovery":
                    recovery(snapshot)
                case "progress":
                    progress(snapshot)
                default:
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
        .navigationTitle(snapshot.walletTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func importWallet(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
        Text(snapshot.walletTitle)
            .font(.title2.bold())
        Text(snapshot.walletBody)
            .foregroundStyle(.secondary)

        SecureField(
            snapshot.phraseHint,
            text: Binding(
                get: { snapshot.recoveryPhrase },
                set: model.controller.updateRecoveryPhrase
            ),
            prompt: Text(snapshot.phraseLabel)
        )
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .submitLabel(.done)
        .privacySensitive()
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))

        Text(snapshot.storageNote)
            .font(.footnote)
            .foregroundStyle(.secondary)

        if let error = snapshot.walletError {
            Text(error)
                .font(.footnote)
                .foregroundStyle(.red)
                .accessibilityAddTraits(.isStaticText)
        }

        Button(snapshot.importTitle, action: model.controller.importWallet)
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .disabled(!snapshot.canImport)
    }

    @ViewBuilder
    private func recovery(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
        Text(snapshot.walletTitle)
            .font(.title2.bold())
        Text(snapshot.walletBody)
            .foregroundStyle(.secondary)

        if let error = snapshot.walletError {
            Text(error)
                .font(.footnote)
                .foregroundStyle(.red)
        }

        Button(snapshot.retryTitle, action: model.controller.retryWallet)
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)

        Button(snapshot.resetTitle, role: .destructive, action: model.controller.requestReset)
            .buttonStyle(.bordered)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
    }

    private func progress(_ snapshot: FlintNativeOnboardingSnapshot) -> some View {
        VStack(spacing: 16) {
            ProgressView()
            if let status = snapshot.walletStatus {
                Text(status)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 240)
    }

    private func featurePages(
        _ snapshot: FlintNativeOnboardingSnapshot
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

    private func removalPresented(
        _ snapshot: FlintNativeOnboardingSnapshot
    ) -> Binding<Bool> {
        Binding(
            get: { snapshot.confirmRemoval },
            set: { presented in
                if !presented {
                    model.controller.cancelReset()
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
}
