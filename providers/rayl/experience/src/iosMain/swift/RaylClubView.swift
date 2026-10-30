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
            case "choose":
                Text(snapshot.chooseTitle)
                    .font(.title2.bold())
                ForEach(snapshot.offers, id: \.tierCode) { offer in
                    VStack(spacing: 8) {
                        Text(offer.name)
                            .font(.headline)
                        Text(offer.terms)
                            .foregroundStyle(.secondary)
                        Button(offer.joinTitle) { model.controller.join(tierCode: offer.tierCode) }
                            .buttonStyle(.borderedProminent)
                            .disabled(snapshot.busy)
                    }
                    .frame(maxWidth: .infinity)
                }
                errorText(snapshot)
                signOut(snapshot)
            case "pay":
                Text(snapshot.payTitle)
                    .font(.title2.bold())
                if let amount = snapshot.payAmount {
                    Text(amount)
                }
                Button(snapshot.payWithRaylTitle) { model.controller.payFee() }
                    .buttonStyle(.borderedProminent)
                if let invoice = snapshot.invoice {
                    Button(snapshot.copyInvoiceTitle) { UIPasteboard.general.string = invoice }
                        .buttonStyle(.bordered)
                }
                Text(snapshot.waitingTitle)
                    .foregroundStyle(.secondary)
                errorText(snapshot)
                signOut(snapshot)
            case "member":
                Text(snapshot.memberTitle)
                    .font(.title2.bold())
                ForEach([snapshot.tierLine, snapshot.creditLine, snapshot.creditAvailable, snapshot.validUntil, snapshot.owed, snapshot.statementLine].compactMap { $0 }, id: \.self) {
                    Text($0)
                }
                memberId(snapshot)
                if let line = snapshot.repaymentLine, let invoice = snapshot.repaymentInvoice {
                    Text(line)
                    Button(snapshot.payWithRaylTitle) { model.controller.payRepayment() }
                        .buttonStyle(.borderedProminent)
                    Button(snapshot.copyInvoiceTitle) { UIPasteboard.general.string = invoice }
                        .buttonStyle(.bordered)
                    Text(snapshot.waitingTitle)
                        .foregroundStyle(.secondary)
                } else if let repayTitle = snapshot.repayTitle {
                    Button(repayTitle) { model.controller.repay() }
                        .buttonStyle(.borderedProminent)
                        .disabled(snapshot.busy)
                }
                NavigationLink(snapshot.statementTitle) {
                    RaylClubStatementView(controller: model.controller)
                }
                .buttonStyle(.bordered)
                errorText(snapshot)
                signOut(snapshot)
            case "none":
                // Joining is not offered in this app (D-16): status only, no directions elsewhere.
                Text(snapshot.noMembership)
                memberId(snapshot)
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

    @ViewBuilder
    private func memberId(_ snapshot: RaylNativeClubSnapshot) -> some View {
        if let line = snapshot.memberIdLine {
            Text(line)
                .foregroundStyle(.secondary)
        }
    }

    private func signOut(_ snapshot: RaylNativeClubSnapshot) -> some View {
        Button(snapshot.signOutTitle) { model.controller.signOut() }
            .disabled(snapshot.busy)
    }
}

@MainActor
final class RaylClubStatementModel: ObservableObject {
    @Published private(set) var snapshot: RaylNativeClubStatementSnapshot?

    let controller: RaylNativeClubController
    private var cancel: (() -> Void)?

    init(controller: RaylNativeClubController) {
        self.controller = controller
        cancel = controller.observeStatement { [weak self] in self?.snapshot = $0 }
    }

    deinit { cancel?() }
}

/** The latest statement and what happened since (US-20). Plain screen until designs exist. */
private struct RaylClubStatementView: View {
    @StateObject private var model: RaylClubStatementModel

    init(controller: RaylNativeClubController) {
        _model = StateObject(wrappedValue: RaylClubStatementModel(controller: controller))
    }

    var body: some View {
        List {
            if let snapshot = model.snapshot {
                switch snapshot.stage {
                case "failed":
                    Text(snapshot.error)
                        .foregroundStyle(.red)
                    Button(snapshot.retryTitle) { model.controller.loadStatement() }
                case "loaded":
                    if let none = snapshot.noStatement {
                        Text(none)
                            .foregroundStyle(.secondary)
                    }
                    if let closed = snapshot.closedTitle {
                        Section(closed) {
                            if let summary = snapshot.summary {
                                Text(summary)
                            }
                            lines(snapshot.lines, empty: snapshot.empty)
                        }
                    }
                    Section(snapshot.sinceTitle ?? "") {
                        lines(snapshot.currentLines, empty: snapshot.empty)
                    }
                default:
                    ProgressView()
                }
            }
        }
        .navigationTitle(model.snapshot?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.controller.loadStatement() }
    }

    @ViewBuilder
    private func lines(_ lines: [RaylNativeClubStatementLine], empty: String) -> some View {
        if lines.isEmpty {
            Text(empty)
                .foregroundStyle(.secondary)
        }
        ForEach(lines, id: \.id) { line in
            HStack {
                VStack(alignment: .leading) {
                    Text(line.title)
                    if let date = line.date {
                        Text(date)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(line.amount)
            }
        }
    }
}
