import SwiftUI

/// The common top-level wallet removal action. Provider text carries credential-specific warnings.
struct NativeWalletRemovalAction: View {
    let title: String
    let confirmationTitle: String
    let message: String
    let cancelTitle: String
    let confirmTitle: String
    let canRemove: Bool
    let onRemove: () -> Void

    @State private var confirmsRemoval = false

    var body: some View {
        Button(role: .destructive) {
            confirmsRemoval = true
        } label: {
            Label(title, systemImage: "trash")
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("settings_remove_wallet")
        .alert(confirmationTitle, isPresented: $confirmsRemoval) {
            Button(cancelTitle, role: .cancel) {}
            Button(confirmTitle, role: .destructive, action: onRemove)
                .disabled(!canRemove)
        } message: {
            Text(message)
        }
    }
}
