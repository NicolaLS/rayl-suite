import Shared
import SwiftUI
import UIKit

@MainActor
private final class BlinkNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: BlinkNativeOnboardingSnapshot?

    let controller: BlinkNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: BlinkNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

/// Blink's native onboarding. Kotlin owns settings and wallet work; SwiftUI owns presentation.
struct BlinkNativeOnboardingView: View {
    @StateObject private var model: BlinkNativeOnboardingModel
    @State private var apiKeyVisible = false
    @FocusState private var apiKeyFocused: Bool

    init(controller: BlinkNativeOnboardingController) {
        _model = StateObject(wrappedValue: BlinkNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .navigationBarTitleDisplayMode(.inline)
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
                } else {
                    ProgressView()
                }
            }
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
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
                action: model.controller.continueFeatures
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

    private func instructions(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
        NativeOnboardingProgressLayout(
            stepIndex: 0,
            stepCount: 0
        ) {
            VStack(spacing: 10) {
                Text(snapshot.instructionsTitle)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text(snapshot.instructionsIntro)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                TabView(
                    selection: Binding(
                        get: { snapshot.instructionPage },
                        set: model.controller.setInstructionPage
                    )
                ) {
                    ForEach(
                        Array(snapshot.instructionPages.enumerated()),
                        id: \.offset
                    ) { index, page in
                        instructionPage(page, number: index + 1)
                            .tag(Int32(index))
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))

                HStack {
                    Button(snapshot.previousStepTitle) {
                        model.controller.setInstructionPage(
                            page: snapshot.instructionPage - 1
                        )
                    }
                    .disabled(snapshot.instructionPage == 0)

                    Spacer()
                    Text(snapshot.instructionProgress)
                        .font(.subheadline.weight(.semibold))
                    Spacer()

                    Button(snapshot.nextStepTitle) {
                        model.controller.setInstructionPage(
                            page: snapshot.instructionPage + 1
                        )
                    }
                    .disabled(
                        snapshot.instructionPage >= snapshot.instructionPages.count - 1
                    )
                }
            }

            Link(destination: URL(string: snapshot.dashboardUrl)!) {
                Text(snapshot.dashboardTitle)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button(action: model.controller.showWalletConnection) {
                Text(snapshot.enterKeyTitle)
                    .frame(maxWidth: .infinity, minHeight: 28)
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.roundedRectangle(radius: 16))
            .controlSize(.large)
        }
    }

    private func instructionPage(
        _ page: BlinkNativeOnboardingPage,
        number: Int
    ) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Text(String(number))
                    .font(.headline)
                    .foregroundStyle(Color.white)
                    .frame(width: 32, height: 32)
                    .background(Color.accentColor, in: Circle())
                Text(page.title)
                    .font(.headline)
                Spacer()
            }

            if let imageName = page.imageName,
                let image = UIImage(named: imageName)
            {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            Text(page.body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 2)
    }

    private func wallet(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(snapshot.walletTitle)
                    .font(.largeTitle.bold())
                Text(snapshot.walletDescription)
                    .foregroundStyle(.secondary)

                Button(
                    snapshot.walletInstructionsTitle,
                    action: model.controller.showWalletInstructions
                )
                .disabled(snapshot.isConnecting)

                VStack(alignment: .leading, spacing: 4) {
                    Text(snapshot.connectionNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 20) {
                        if let value = snapshot.privacyPolicyUrl, let url = URL(string: value) {
                            Link(snapshot.privacyTitle, destination: url)
                                .frame(minHeight: 44)
                        }
                        if let value = snapshot.termsUrl, let url = URL(string: value) {
                            Link(snapshot.termsTitle, destination: url)
                                .frame(minHeight: 44)
                        }
                    }
                    .font(.footnote)
                }

                VStack(alignment: .trailing, spacing: 8) {
                    HStack {
                        Group {
                            if apiKeyVisible {
                                TextField(
                                    snapshot.apiKeyPlaceholder,
                                    text: apiKeyBinding(snapshot)
                                )
                            } else {
                                SecureField(
                                    snapshot.apiKeyPlaceholder,
                                    text: apiKeyBinding(snapshot)
                                )
                            }
                        }
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .focused($apiKeyFocused)
                        .submitLabel(.go)
                        .onSubmit {
                            if snapshot.canConnect {
                                apiKeyFocused = false
                                model.controller.connectWallet()
                            }
                        }
                        .accessibilityLabel(snapshot.walletTitle)
                        .disabled(snapshot.isConnecting)

                        Button {
                            apiKeyVisible.toggle()
                        } label: {
                            Image(systemName: apiKeyVisible ? "eye.slash" : "eye")
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(
                            apiKeyVisible
                                ? snapshot.hideApiKeyTitle
                                : snapshot.showApiKeyTitle
                        )
                    }
                    .padding()
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 16))

                    Button(action: model.controller.pasteApiKey) {
                        Label(snapshot.pasteTitle, systemImage: "doc.on.clipboard")
                            .padding(.vertical, 8)
                    }
                    .disabled(snapshot.isConnecting)
                }

                if let error = snapshot.connectionError {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

            }
            .padding(24)
            .frame(maxWidth: 608, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .onDisappear {
            apiKeyVisible = false
            apiKeyFocused = false
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                apiKeyFocused = false
                model.controller.connectWallet()
            } label: {
                HStack {
                    if snapshot.isConnecting {
                        ProgressView()
                    }
                    Text(snapshot.connectTitle)
                }
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 28)
                .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 16))
            .controlSize(.large)
            .disabled(!snapshot.canConnect)
            .padding(24)
            .frame(maxWidth: 608)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
        }
    }

    private func apiKeyBinding(_ snapshot: BlinkNativeOnboardingSnapshot) -> Binding<String> {
        Binding(
            get: { snapshot.apiKey },
            set: { model.controller.updateApiKey(apiKey: $0) }
        )
    }

    private func featurePages(
        _ snapshot: BlinkNativeOnboardingSnapshot
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
}
