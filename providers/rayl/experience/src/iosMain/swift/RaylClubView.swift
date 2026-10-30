import AuthenticationServices
import SwiftUI
import Shared

@MainActor
final class RaylClubModel: ObservableObject {
    @Published private(set) var snapshot: RaylNativeClubSnapshot?

    let controller: RaylNativeClubController
    private var cancel: (() -> Void)?

    init(controller: RaylNativeClubController) {
        self.controller = controller
        cancel = controller.observe { [weak self] in self?.snapshot = $0 }
    }

    deinit { cancel?() }
}

/** The Rayl Club row in Rayl's settings. */
struct RaylClubRow: View {
    @ObservedObject var model: RaylClubModel

    var body: some View {
        NavigationLink {
            RaylClubView(model: model)
        } label: {
            HStack {
                Label(model.snapshot?.title ?? "", systemImage: "crown")
                Spacer()
                Text(model.snapshot?.status ?? "")
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/** Sign in, redeem an invite, and see the invite's tiers. Plain S0 screens until designs exist. */
private struct RaylClubView: View {
    @ObservedObject var model: RaylClubModel
    @State private var code = ""

    var body: some View {
        ScrollView {
            if let snapshot = model.snapshot {
                content(snapshot)
                    .padding(24)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(model.snapshot?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func content(_ snapshot: RaylNativeClubSnapshot) -> some View {
        VStack(spacing: 20) {
            switch snapshot.stage {
            case "signedOut":
                Text(snapshot.intro)
                    .multilineTextAlignment(.center)
                errorText(snapshot)
                if snapshot.busy {
                    ProgressView()
                } else {
                    appleSignIn
                }
            case "accountFailed":
                errorText(snapshot)
                Button(snapshot.retryTitle) { model.controller.reload() }
                    .buttonStyle(.borderedProminent)
                signOut(snapshot)
            case "invite":
                Text(snapshot.inviteBody)
                    .multilineTextAlignment(.center)
                TextField(snapshot.inviteCodeLabel, text: $code)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .onChange(of: code) { _, value in
                        let limited = String(value.prefix(24))
                        if limited != value { code = limited }
                        if snapshot.error != nil { model.controller.dismissError() }
                    }
                errorText(snapshot)
                Button {
                    model.controller.redeem(code: code)
                } label: {
                    if snapshot.busy {
                        ProgressView()
                    } else {
                        Text(snapshot.redeemTitle)
                    }
                }
                .buttonStyle(.bordered)
                .disabled(snapshot.busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
                signOut(snapshot)
            case "invited":
                Text(snapshot.invitedTitle)
                    .font(.title2.bold())
                Text(snapshot.invitedBody)
                    .multilineTextAlignment(.center)
                if let tiers = snapshot.tiers {
                    Text(tiers)
                }
                if let feeWaived = snapshot.feeWaived {
                    Text(feeWaived)
                }
                signOut(snapshot)
            default:
                ProgressView()
            }
        }
    }

    /** Requests no name or email: the Club account is pseudonymous. */
    private var appleSignIn: some View {
        SignInWithAppleButton(.signIn) { request in
            request.requestedScopes = []
            request.nonce = model.controller.appleRequestNonce()
        } onCompletion: { result in
            switch result {
            case .success(let authorization):
                if let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                   let data = credential.identityToken,
                   let token = String(data: data, encoding: .utf8) {
                    model.controller.completeAppleSignIn(idToken: token)
                } else {
                    model.controller.failAppleSignIn(cancelled: false)
                }
            case .failure(let error):
                let cancelled = (error as? ASAuthorizationError)?.code == .canceled
                model.controller.failAppleSignIn(cancelled: cancelled)
            }
        }
        .frame(height: 48)
    }

    @ViewBuilder
    private func errorText(_ snapshot: RaylNativeClubSnapshot) -> some View {
        if let error = snapshot.error {
            Text(error)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
    }

    private func signOut(_ snapshot: RaylNativeClubSnapshot) -> some View {
        Button(snapshot.signOutTitle) { model.controller.signOut() }
            .disabled(snapshot.busy)
    }
}
