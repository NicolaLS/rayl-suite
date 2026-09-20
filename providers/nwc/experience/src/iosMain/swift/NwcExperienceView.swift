import SwiftUI
import Shared

private func makeNwcShellAdapter(_ experience: NwcIosExperience) -> NativeShellAdapter {
    NativeShellAdapter(
    visibleTabIds: ["scan", "recent", "hub", "settings"],
    initialStage: {
        experience.isOnboarded() ? .tabs : .onboarding
    },
    initialSelectedTab: experience.selectedTab,
    selectTab: { experience.selectTab(tab: $0) },
    observeStage: { observer in
        experience.observeOnboarded { value in
            observer.send(value.boolValue ? .tabs : .onboarding)
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
                    NwcWalletSettingsLink(
                        controller: experience.walletSettingsController(),
                        onboardingController: experience.onboardingController()
                    )
                )
            )
        )
    },
    nativeOnboardingView: {
        AnyView(
            NwcNativeOnboardingView(controller: experience.onboardingController())
        )
    }
)

}

@MainActor
private final class NwcRemovalModel: ObservableObject {
    @Published private(set) var snapshot: NwcRemovalSnapshot?
    private var cancel: (() -> Void)?

    init(experience: NwcIosExperience) {
        cancel = experience.observeRemoval { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit { cancel?() }
}

struct NwcExperienceView: View {
    let experience: NwcIosExperience
    private let adapter: NativeShellAdapter
    @StateObject private var removal: NwcRemovalModel

    init(experience: NwcIosExperience) {
        self.experience = experience
        self.adapter = makeNwcShellAdapter(experience)
        _removal = StateObject(wrappedValue: NwcRemovalModel(experience: experience))
    }

    var body: some View {
        if let snapshot = removal.snapshot {
            if snapshot.pending {
                NativeWalletRemovalRecoveryView(
                    title: snapshot.title,
                    message: snapshot.message,
                    retryTitle: snapshot.retryTitle,
                    isWorking: snapshot.isWorking,
                    onRetry: experience.retryRemoval
                )
            } else {
                NativeAppShell(adapter: adapter)
            }
        } else {
            ProgressView()
        }
    }
}

@MainActor
private final class NwcNativeWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: NwcNativeWalletSettingsSnapshot?

    let controller: NwcNativeWalletSettingsController
    private var cancel: (() -> Void)?

    init(controller: NwcNativeWalletSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

private struct NwcWalletSettingsLink: View {
    @StateObject private var model: NwcNativeWalletSettingsModel
    let onboardingController: NwcNativeOnboardingController

    init(
        controller: NwcNativeWalletSettingsController,
        onboardingController: NwcNativeOnboardingController
    ) {
        _model = StateObject(wrappedValue: NwcNativeWalletSettingsModel(controller: controller))
        self.onboardingController = onboardingController
    }

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                NativeWalletSettingsLink(
                    title: snapshot.settingsTitle,
                    subtitle: snapshot.settingsSubtitle
                ) {
                    NwcWalletManagementView(snapshot: snapshot, model: model)
                }
            } else {
                ProgressView()
            }
        }
        .sheet(isPresented: walletFlowPresented) {
            NwcNativeOnboardingView(controller: onboardingController)
        }
    }

    private var walletFlowPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot?.walletFlowPresented == true },
            set: { presented in
                if !presented {
                    model.controller.finishWalletConnection()
                }
            }
        )
    }
}

private struct NwcWalletManagementView: View {
    let snapshot: NwcNativeWalletSettingsSnapshot
    @ObservedObject var model: NwcNativeWalletSettingsModel

    var body: some View {
        NativeWalletManagementView(
            text: snapshot.managementText,
            wallet: snapshot.managedWallet,
            isWorking: snapshot.isWorking,
            errorMessage: snapshot.removalErrorMessage,
            onAddWallet: model.controller.requestWalletConnection,
            walletDetails: {
                NwcWalletDetailsView(snapshot: snapshot)
            },
            showsWalletDetails: snapshot.walletId != nil,
            onRemoveWallet: model.controller.removeWallet
        )
    }
}

private struct NwcWalletDetailsView: View {
    let snapshot: NwcNativeWalletSettingsSnapshot

    var body: some View {
        List {
            if let title = snapshot.walletTitle {
                Section {
                    Text(title)
                        .font(.headline)
                        .textSelection(.enabled)
                }
            }

            Section {
                detail(snapshot.walletTypeLabel, snapshot.walletType)
                if let connectionId = snapshot.walletId {
                    detail(snapshot.connectionIdLabel, connectionId)
                }
            }
        }
        .navigationTitle(snapshot.detailsTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detail(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
    }
}

private extension NwcNativeWalletSettingsSnapshot {
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
