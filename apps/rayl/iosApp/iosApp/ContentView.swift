import Shared
import SwiftUI

@MainActor
private final class RaylModel: ObservableObject {
    @Published private(set) var snapshot: RaylSnapshot?
    private var cancel: (() -> Void)?
    init() { cancel = RaylIosApp.shared.observe { [weak self] in self?.snapshot = $0 } }
    deinit { cancel?() }
}

struct ContentView: View {
    @StateObject private var model = RaylModel()

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                VStack(spacing: 0) {
                    if snapshot.canCancelSetup {
                        HStack {
                            Button(snapshot.text["choose_another"] ?? "") { RaylIosApp.shared.cancelSetup() }
                            Spacer()
                        }.padding()
                    }
                    if snapshot.closingSpark || snapshot.sparkCloseFailed {
                        VStack(spacing: 16) {
                            if snapshot.sparkCloseFailed {
                                Text(snapshot.text["session_close_failed"] ?? "")
                                Button(snapshot.text["session_close_retry"] ?? "") {
                                    RaylIosApp.shared.retrySparkClose()
                                }
                            } else {
                                ProgressView()
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if let experience = snapshot.blinkExperience {
                        BlinkExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if let experience = snapshot.nwcExperience {
                        NwcExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if let experience = snapshot.sparkExperience {
                        SparkExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else {
                        selection(snapshot)
                    }
                }
                .id(snapshot.wallet)
                .preferredColorScheme(colorScheme(snapshot.colorScheme))
                .alert(
                    "Rayl",
                    isPresented: Binding(
                        get: { snapshot.message != nil },
                        set: { if !$0 { RaylIosApp.shared.dismissMessage() } })
                ) {
                    Button(snapshot.text["close"] ?? "") { RaylIosApp.shared.dismissMessage() }
                } message: {
                    Text(snapshot.message ?? "")
                }
            } else {
                ProgressView()
            }
        }
    }

    private func colorScheme(_ value: String) -> ColorScheme? {
        switch value {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    @ViewBuilder
    private func selection(_ snapshot: RaylSnapshot) -> some View {
        if !snapshot.welcomeCompleted {
            NativeOnboardingWelcomeView(
                title: snapshot.text["welcome_title"] ?? "",
                subtitle: "",
                description: snapshot.text["welcome_body"] ?? "",
                actionTitle: snapshot.text["get_started"] ?? "",
                action: { RaylIosApp.shared.completeWelcome() }
            )
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Image(systemName: "wallet.bifold")
                        .font(.system(size: 36, weight: .medium))
                        .foregroundStyle(.tint)
                        .frame(width: 80, height: 80)
                        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 24))
                        .accessibilityHidden(true)
                    Text(snapshot.text["choose_wallet"] ?? "")
                        .font(.largeTitle.bold())
                    Text(snapshot.text["choose_body"] ?? "")
                        .foregroundStyle(.secondary)
                    ForEach(snapshot.wallets, id: \.self) { wallet in
                        choice(wallet, snapshot)
                    }
                }
                .padding(24)
                .frame(maxWidth: 608, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
        }
    }

    private func choice(_ wallet: String, _ snapshot: RaylSnapshot) -> some View {
        Button {
            RaylIosApp.shared.choose(wallet: wallet)
        } label: {
            HStack(spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(snapshot.text[wallet + "_title"] ?? "")
                        .font(.title2.bold())
                        .foregroundStyle(.primary)
                    Text(snapshot.text[wallet + "_body"] ?? "")
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "arrow.right")
                    .font(.headline)
                    .foregroundStyle(.tint)
            }
            .multilineTextAlignment(.leading)
            .padding(24)
            .background(
                Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24)
            )
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
    }
}
