import AppKit

/// The one-time setup window: installs Python, the packages and the voice models (app/setup.sh).
final class Setup: NSObject {
    let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 480, height: 190),
                          styleMask: [.titled], backing: .buffered, defer: false)
    let title = NSTextField(labelWithString: "Setting up Folio")
    let detail = NSTextField(wrappingLabelWithString: "")
    let step = NSTextField(labelWithString: "Starting…")
    let bar = NSProgressIndicator()
    let quit = NSButton(title: "Quit", target: nil, action: nil)
    let retry = NSButton(title: "Try Again", target: nil, action: nil)
    var onRetry: (() -> Void)?

    override init() {
        super.init()
        window.title = "Folio"
        window.isReleasedWhenClosed = false     // Swift owns it; AppKit releasing it too crashes on close
        let icon = NSImageView(image: NSApp.applicationIconImage)
        title.font = .boldSystemFont(ofSize: 15)
        detail.stringValue = "This happens once. Folio downloads Python and its speech engine (about 1.5 GB) "
            + "so everything runs on this Mac. It takes a few minutes."
        detail.textColor = .secondaryLabelColor
        step.textColor = .secondaryLabelColor
        step.lineBreakMode = .byTruncatingTail
        bar.isIndeterminate = true
        bar.style = .bar
        quit.target = NSApp
        quit.action = #selector(NSApplication.terminate(_:))
        retry.target = self
        retry.action = #selector(again)
        retry.keyEquivalent = "\r"
        retry.isHidden = true
        let text = NSStackView(views: [title, detail, bar, step])
        text.orientation = .vertical
        text.alignment = .leading
        text.spacing = 8
        let row = NSStackView(views: [icon, text])
        row.alignment = .top
        row.spacing = 16
        let buttons = NSStackView(views: [quit, retry])
        let all = NSStackView(views: [row, buttons])
        all.orientation = .vertical
        all.alignment = .trailing
        all.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 16, right: 20)
        window.contentView = all
        NSLayoutConstraint.activate([
            icon.widthAnchor.constraint(equalToConstant: 64), icon.heightAnchor.constraint(equalToConstant: 64),
            text.widthAnchor.constraint(equalToConstant: 360), bar.widthAnchor.constraint(equalTo: text.widthAnchor),
            detail.widthAnchor.constraint(equalTo: text.widthAnchor), step.widthAnchor.constraint(equalTo: text.widthAnchor),
        ])
    }

    func show() {
        NSApp.setActivationPolicy(.regular)
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        bar.startAnimation(nil)
    }

    func failed(_ message: String) {
        bar.stopAnimation(nil)
        bar.isHidden = true
        title.stringValue = "Setup didn't finish"
        step.lineBreakMode = .byWordWrapping
        step.maximumNumberOfLines = 0
        step.stringValue = message
        retry.isHidden = false
    }

    @objc func again() {
        retry.isHidden = true
        title.stringValue = "Setting up Folio"
        step.lineBreakMode = .byTruncatingTail
        step.maximumNumberOfLines = 1
        bar.isHidden = false
        bar.startAnimation(nil)
        onRetry?()
    }
}
