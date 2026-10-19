import AppKit
import WebKit

/// Folio's window: the interface served by the engine on 127.0.0.1, in a native window. Handles what a
/// browser would: file pickers (Import), saving exports, confirm/prompt dialogs, and outside links, which
/// open in the default browser.
final class MainWindow: NSObject, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    let window: NSWindow
    let web: WKWebView
    private let loading = NSView()
    private let loadingText = NSTextField(labelWithString: "Starting Folio…")
    private let spinner = NSProgressIndicator()
    private var live = false            // the interface is loaded (not blank because the window was closed)
    private let home = URL(string: "http://127.0.0.1:\(port)/")!

    override init() {
        let cfg = WKWebViewConfiguration()
        cfg.applicationNameForUserAgent = "FolioApp/\(appVersion)"
        cfg.mediaTypesRequiringUserActionForPlayback = []
        web = WKWebView(frame: .zero, configuration: cfg)
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1240, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        super.init()
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsMagnification = true
        window.title = "Folio"
        window.minSize = NSSize(width: 760, height: 480)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        window.setFrameAutosaveName("FolioMain")
        window.tabbingMode = .disallowed

        spinner.style = .spinning
        spinner.controlSize = .regular
        loadingText.textColor = .secondaryLabelColor
        let stack = NSStackView(views: [spinner, loadingText])
        stack.orientation = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        loading.addSubview(stack)
        NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo: loading.centerXAnchor),
                                     stack.centerYAnchor.constraint(equalTo: loading.centerYAnchor)])
    }

    /// While the engine starts.
    func showLoading(_ text: String = "Starting Folio…") {
        loadingText.stringValue = text
        spinner.startAnimation(nil)
        window.contentView = loading
        front()
    }

    /// The engine is up: show (and if needed load) the interface.
    func showInterface() {
        if !live { web.load(URLRequest(url: home)); live = true }
        if window.contentView !== web { window.contentView = web; spinner.stopAnimation(nil) }
        front()
    }

    func front() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func go(_ hash: String) {
        showInterface()
        web.evaluateJavaScript("location.hash = \(String(reflecting: hash))")
    }

    // Closing the window keeps Folio running (a recording carries on); unloading the page tells the
    // engine no window is open, so it notifies when a transcript is ready.
    func windowWillClose(_ notification: Notification) {
        if live { web.loadHTMLString("", baseURL: nil); live = false }
    }

    // --- links: Folio's own pages stay here; anything else opens in the browser

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { return decisionHandler(.cancel) }
        if url.host == "127.0.0.1" || url.scheme == "about" || url.scheme == "blob" || url.scheme == "data" {
            return decisionHandler(action.shouldPerformDownload ? .download : .allow)
        }
        if ["http", "https", "mailto"].contains(url.scheme ?? "") { NSWorkspace.shared.open(url) }
        decisionHandler(.cancel)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { NSWorkspace.shared.open(url) }   // target=_blank
        return nil
    }

    // --- exports (the engine sends them as attachments): ask where to save

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        let disposition = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition") ?? ""
        decisionHandler(disposition.lowercased().hasPrefix("attachment") || !response.canShowMIMEType ? .download : .allow)
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = suggestedFilename
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        panel.beginSheetModal(for: window) { r in
            guard r == .OK, let url = panel.url else { return completionHandler(nil) }
            try? FileManager.default.removeItem(at: url)        // the panel already asked about replacing it
            completionHandler(url)
        }
    }

    // --- Import: the file picker

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.beginSheetModal(for: window) { r in completionHandler(r == .OK ? panel.urls : nil) }
    }

    // --- alert / confirm / prompt, as Mac dialogs

    private func alert(_ message: String, buttons: [String], field: NSTextField? = nil) -> NSApplication.ModalResponse {
        let a = NSAlert()
        a.messageText = message
        for b in buttons { a.addButton(withTitle: b) }
        if let field { a.accessoryView = field; a.window.initialFirstResponder = field }
        return a.runModal()
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        _ = alert(message, buttons: ["OK"])
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        completionHandler(alert(message, buttons: ["OK", "Cancel"]) == .alertFirstButtonReturn)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        field.stringValue = defaultText ?? ""
        completionHandler(alert(prompt, buttons: ["OK", "Cancel"], field: field) == .alertFirstButtonReturn ? field.stringValue : nil)
    }

    // Testing: FOLIO_SNAPSHOT=<file.png> saves a picture of the window once the interface has loaded.
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let path = env["FOLIO_SNAPSHOT"], live else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            webView.takeSnapshot(with: nil) { image, _ in
                guard let tiff = image?.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return }
                try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
            }
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if live { webView.reload() }
    }

    // --- View menu

    @objc func reload(_ sender: Any?) { if live { web.reload() } }
    @objc func zoomIn(_ sender: Any?) { web.pageZoom = min(web.pageZoom + 0.1, 2) }
    @objc func zoomOut(_ sender: Any?) { web.pageZoom = max(web.pageZoom - 0.1, 0.6) }
    @objc func actualSize(_ sender: Any?) { web.pageZoom = 1 }
}
