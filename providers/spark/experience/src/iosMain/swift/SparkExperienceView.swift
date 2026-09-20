import SwiftUI
import Shared

private func makeSparkShellAdapter(_ experience: SparkIosExperience) -> NativeShellAdapter {
    NativeShellAdapter(
        visibleTabIds: ["scan", "recent", "hub", "settings"],
        initialStage: {
            switch experience.stage() {
            case "tabs": return .tabs
            case "onboarding": return .onboarding
            default: return .loading
            }
        },
        initialSelectedTab: experience.selectedTab,
        selectTab: { experience.selectTab(tab: $0) },
        observeStage: { observer in
            experience.observeStage { value in
                switch value {
                case "tabs": observer.send(.tabs)
                case "onboarding": observer.send(.onboarding)
                default: observer.send(.loading)
                }
            }
        },
        observeSelectedTab: { observer in
            experience.observeSelectedTab(onChange: observer.send)
        },
        observeTheme: { observer in
            experience.observeTheme(onChange: observer.send)
        },
        initialRecentBadgeCount: { Int(experience.recentBadgeCount()) },
        observeRecentBadgeCount: { observer in
            experience.observeRecentBadgeCount { value in
                observer.send(Int(value.intValue))
            }
        },
        observeTabTitles: { observer in
            experience.observeTabTitles(onChange: observer.send)
        },
        nativeScanView: {
            AnyView(NativePaymentScanView(controller: experience.scanController()))
        },
        nativeRecentView: {
            AnyView(NativePaymentRecentView(controller: experience.recentController()))
        },
        nativeHubView: {
            AnyView(NativePaymentHubView(controller: experience.hubController()))
        },
        nativeSettingsView: {
            AnyView(
                NativeSettingsView(
                    controller: experience.settingsController(),
                    leadingContent: AnyView(
                        SparkWalletSettingsLink(
                            controller: experience.walletSettingsController()
                        )
                    )
                )
            )
        },
        nativeOnboardingView: {
            AnyView(SparkNativeOnboardingView(controller: experience.onboardingController()))
        }
    )
}

struct SparkExperienceView: View {
    private let adapter: NativeShellAdapter

    init(experience: SparkIosExperience) {
        adapter = makeSparkShellAdapter(experience)
    }

    var body: some View {
        NativeAppShell(adapter: adapter)
    }
}

@MainActor
private final class SparkNativeWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: SparkNativeWalletSettingsSnapshot?

    let controller: SparkNativeWalletSettingsController
    private var cancel: (() -> Void)?

    init(controller: SparkNativeWalletSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

private struct SparkWalletSettingsLink: View {
    @StateObject private var model: SparkNativeWalletSettingsModel

    init(controller: SparkNativeWalletSettingsController) {
        _model = StateObject(wrappedValue: SparkNativeWalletSettingsModel(controller: controller))
    }

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                NativeWalletSettingsLink(
                    title: snapshot.settingsTitle,
                    subtitle: snapshot.settingsSubtitle
                ) {
                    SparkWalletManagementView(snapshot: snapshot, model: model)
                }
            } else {
                ProgressView()
            }
        }
    }
}

private struct SparkWalletManagementView: View {
    let snapshot: SparkNativeWalletSettingsSnapshot
    @ObservedObject var model: SparkNativeWalletSettingsModel

    var body: some View {
        NativeWalletManagementView(
            text: snapshot.managementText,
            wallet: snapshot.managedWallet,
            isWorking: snapshot.isWorking,
            errorMessage: snapshot.errorMessage,
            onAddWallet: {},
            walletDetails: { EmptyView() },
            showsWalletDetails: false,
            onRemoveWallet: model.controller.removeWallet
        )
    }
}

private extension SparkNativeWalletSettingsSnapshot {
    var managementText: NativeWalletManagementTextValue {
        NativeWalletManagementTextValue(
            screenTitle: screenTitle,
            emptyDescription: emptyDescription,
            addTitle: addTitle,
            removeTitle: removeTitle,
            removeConfirmationTitle: removeConfirmationTitle,
            removeConfirmationBody: removeConfirmationBody,
            cancelTitle: cancelTitle
        )
    }

    var managedWallet: NativeManagedWalletValue? {
        guard let walletId, let walletTitle else { return nil }
        return NativeManagedWalletValue(
            id: walletId,
            title: walletTitle,
            details: walletDetails
        )
    }
}
