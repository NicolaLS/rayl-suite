import SafariServices
import SwiftUI
import WebKit

/// A provider's redemption page, such as a gift card voucher, shown inside the app so users never
/// leave Rayl to see what they bought. Private (nothing is stored between pages) and HTTPS only.
struct RedemptionPage: View {
    let url: String
    let title: String
    var height: CGFloat = 520
    @State private var loading = true
    @State private var expanded = false

    var body: some View {
        if let target = URL(string: url), target.scheme == "https" {
            VStack(spacing: 0) {
                HStack {
                    Label(title, systemImage: "gift.fill")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Button { expanded = true } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(title)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                ZStack {
                    PageView(url: target, loading: $loading)
                    if loading { ProgressView() }
                }
                .frame(height: height)
            }
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .sheet(isPresented: $expanded) {
                SafariPage(url: target).ignoresSafeArea()
            }
        }
    }
}

private struct PageView: UIViewRepresentable {
    let url: URL
    @Binding var loading: Bool

    func makeCoordinator() -> Coordinator { Coordinator(loading: $loading) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // No cookies or storage outlive the page, and nothing is shared with Safari.
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .clear
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        private let loading: Binding<Bool>

        init(loading: Binding<Bool>) { self.loading = loading }

        /// The page itself stays on HTTPS; embedded frames may use the usual in-page schemes.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            let scheme = navigationAction.request.url?.scheme?.lowercased() ?? ""
            if scheme == "https" { return .allow }
            let mainFrame = navigationAction.targetFrame?.isMainFrame ?? true
            return !mainFrame && ["about", "blob", "data"].contains(scheme) ? .allow : .cancel
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading.wrappedValue = false }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            loading.wrappedValue = false
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            loading.wrappedValue = false
        }
    }
}

/// The full page, still inside the app.
private struct SafariPage: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
