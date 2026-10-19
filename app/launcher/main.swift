// Folio.app: sets up the engine on first launch (and after updates that need new parts), starts it if
// it isn't running, and opens Folio in the browser. Also answers folio:// links.
import AppKit

let env = ProcessInfo.processInfo.environment
let bundle = Bundle.main
let res = bundle.resourceURL!
let appVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
let port = env["FOLIO_PORT"] ?? "8765"
let support = URL(fileURLWithPath: env["FOLIO_SUPPORT"]
    ?? NSHomeDirectory() + "/Library/Application Support/Folio")
let logs = URL(fileURLWithPath: NSHomeDirectory() + "/Library/Logs")
let afterUpdate = CommandLine.arguments.contains("--after-update")

func read(_ url: URL) -> String? { try? String(contentsOf: url, encoding: .utf8) }

/// GET /api/status, nil if Folio isn't answering.
func status() -> [String: Any]? {
    var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/api/status")!)
    req.timeoutInterval = 1
    var out: [String: Any]?
    let done = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { data, resp, _ in
        if (resp as? HTTPURLResponse)?.statusCode == 200, let data {
            out = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        }
        done.signal()
    }.resume()
    done.wait()
    return out
}

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

final class App: NSObject, NSApplicationDelegate {
    var setup: Setup?

    func applicationDidFinishLaunching(_ note: Notification) {
        // Opened straight from Downloads, macOS runs a read-only copy that couldn't update itself.
        if bundle.bundlePath.contains("/AppTranslocation/") {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            let a = NSAlert()
            a.messageText = "Move Folio to Applications"
            a.informativeText = "Drag Folio into your Applications folder, then open it from there."
            a.runModal()
            NSApp.terminate(nil)
            return
        }
        if status() != nil { return finish(start: false) }
        let want = read(res.appendingPathComponent("setup-stamp"))
        if want != nil, want == read(support.appendingPathComponent("ready")) {
            DispatchQueue.global().async { self.finish(start: true) }
            return
        }
        let s = Setup()
        setup = s
        s.onRetry = { [weak self] in self?.runSetup() }
        s.show()
        runSetup()
    }

    func application(_ app: NSApplication, open urls: [URL]) {
        // folio://open: launching is all it takes; if Folio was already set up, the browser opens.
    }

    func runSetup() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = [res.appendingPathComponent("setup.sh").path, support.path, res.path]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        try? FileManager.default.createDirectory(at: logs, withIntermediateDirectories: true)
        let logURL = logs.appendingPathComponent("Folio-setup.log")
        FileManager.default.createFile(atPath: logURL.path, contents: nil)
        let log = try? FileHandle(forWritingTo: logURL)
        var last = ""
        out.fileHandleForReading.readabilityHandler = { h in
            let data = h.availableData
            guard !data.isEmpty else { return }
            log?.write(data)
            for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
                if line.hasPrefix("step: ") {
                    let text = String(line.dropFirst(6))
                    DispatchQueue.main.async { self.setup?.step.stringValue = text }
                } else if line.hasPrefix("error: ") {
                    last = String(line.dropFirst(7))
                }
            }
        }
        p.terminationHandler = { p in
            out.fileHandleForReading.readabilityHandler = nil
            try? log?.close()
            DispatchQueue.main.async {
                if p.terminationStatus == 0 {
                    self.setup?.step.stringValue = "Starting Folio…"
                    DispatchQueue.global().async { self.finish(start: true) }
                } else {
                    self.setup?.failed((last.isEmpty ? "Something went wrong." : last)
                        + " Details: ~/Library/Logs/Folio-setup.log")
                }
            }
        }
        do { try p.run() } catch { setup?.failed("Couldn't run setup: \(error.localizedDescription)") }
    }

    /// Start the engine if asked, wait until it answers, open the browser, quit the launcher.
    func finish(start: Bool) {
        if start { launchEngine() }
        var up = false
        for _ in 0..<300 where !up {   // the first start loads a lot of Python; give it a minute
            up = status() != nil
            if !up { Thread.sleep(forTimeInterval: 0.2) }
        }
        DispatchQueue.main.async {
            if !up {
                let a = NSAlert()
                a.messageText = "Folio didn't start"
                a.informativeText = "See ~/Library/Logs/Folio.log for details."
                a.runModal()
            } else if !afterUpdate {
                NSWorkspace.shared.open(URL(string: "http://127.0.0.1:\(port)/")!)
            }
            NSApp.terminate(nil)
        }
    }

    func launchEngine() {
        let p = Process()
        p.executableURL = support.appendingPathComponent("venv/bin/python")
        p.arguments = ["-m", "folio"]
        var e = env
        e["PYTHONPATH"] = res.appendingPathComponent("engine").path
        e["PYTHONDONTWRITEBYTECODE"] = "1"
        e["FOLIO_APP"] = bundle.bundlePath
        e["FOLIO_VERSION"] = appVersion
        e["FOLIO_SUPPORT"] = support.path
        e["FOLIO_MODELS"] = support.appendingPathComponent("models").path
        e["FOLIO_CAPTURE"] = bundle.bundleURL.appendingPathComponent("Contents/MacOS/folio-capture").path
        e["FOLIO_PORT"] = port
        p.environment = e
        p.currentDirectoryURL = support
        let logURL = logs.appendingPathComponent("Folio.log")
        if !FileManager.default.fileExists(atPath: logURL.path) {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
        }
        if let log = try? FileHandle(forWritingTo: logURL) {
            log.seekToEndOfFile()
            p.standardOutput = log
            p.standardError = log
        }
        p.standardInput = FileHandle.nullDevice
        try? p.run()
    }
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
