// Folio.app: a Mac app around Folio's engine. On first launch (and after updates that need new parts) it
// installs the engine; then it starts the engine and shows Folio in its own window. Closing the window
// keeps Folio running (recordings carry on); Quit stops the engine too. Also answers folio:// links.
import AppKit

let env = ProcessInfo.processInfo.environment
let bundle = Bundle.main
let res = bundle.resourceURL!
let appVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
let port = env["FOLIO_PORT"] ?? "8765"
let support = URL(fileURLWithPath: env["FOLIO_SUPPORT"]
    ?? NSHomeDirectory() + "/Library/Application Support/Folio")
let logs = URL(fileURLWithPath: NSHomeDirectory() + "/Library/Logs")

func read(_ url: URL) -> String? { try? String(contentsOf: url, encoding: .utf8) }

/// Calls the engine; nil if it isn't answering.
func engine(_ path: String, post: String? = nil, timeout: TimeInterval = 1) -> [String: Any]? {
    var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/api/\(path)")!)
    req.timeoutInterval = timeout
    if let post {
        req.httpMethod = "POST"
        req.httpBody = post.data(using: .utf8)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    }
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

func status() -> [String: Any]? { engine("status") }

final class App: NSObject, NSApplicationDelegate {
    var setup: Setup?
    let main = MainWindow()
    var up = false               // the engine has answered at least once
    var quitting = false
    var misses = 0

    func applicationDidFinishLaunching(_ note: Notification) {
        buildMenu()
        // Opened straight from Downloads, macOS runs a read-only copy that couldn't update itself.
        if bundle.bundlePath.contains("/AppTranslocation/") {
            let a = NSAlert()
            a.messageText = "Move Folio to Applications"
            a.informativeText = "Drag Folio into your Applications folder, then open it from there."
            a.runModal()
            NSApp.terminate(nil)
            return
        }
        if status() != nil { return ready() }
        let want = read(res.appendingPathComponent("setup-stamp"))
        if want != nil, want == read(support.appendingPathComponent("ready")) { return start() }
        let s = Setup()
        setup = s
        s.onRetry = { [weak self] in self?.runSetup() }
        s.show()
        runSetup()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if up { main.showInterface() }
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func application(_ app: NSApplication, open urls: [URL]) {
        if up { main.showInterface() }      // folio://open
    }

    // --- engine

    func start() {
        main.showLoading()
        DispatchQueue.global().async {
            self.launchEngine()
            var ok = false
            for _ in 0..<300 where !ok {    // the first start loads a lot of Python; give it a minute
                ok = status() != nil
                if !ok { Thread.sleep(forTimeInterval: 0.2) }
            }
            DispatchQueue.main.async {
                if ok { return self.ready() }
                let a = NSAlert()
                a.messageText = "Folio didn't start"
                a.informativeText = "See ~/Library/Logs/Folio.log for details."
                a.runModal()
                NSApp.terminate(nil)
            }
        }
    }

    func ready() {
        up = true
        setup?.window.close()
        setup = nil
        main.showInterface()
        // If the engine stops (Quit in Folio's Activity page, or an update restarting it), the app goes too.
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self, !self.quitting else { return }
            DispatchQueue.global().async {
                let alive = status() != nil
                DispatchQueue.main.async {
                    self.misses = alive ? 0 : self.misses + 1
                    if self.misses >= 3 { self.quitting = true; NSApp.terminate(nil) }
                }
            }
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
        try? FileManager.default.createDirectory(at: logs, withIntermediateDirectories: true)
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
                    self.setup?.window.close()
                    self.setup = nil
                    self.start()
                } else {
                    self.setup?.failed((last.isEmpty ? "Something went wrong." : last)
                        + " Details: ~/Library/Logs/Folio-setup.log")
                }
            }
        }
        do { try p.run() } catch { setup?.failed("Couldn't run setup: \(error.localizedDescription)") }
    }

    // --- quitting stops the engine too (after asking, if it's recording or working)

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if quitting || !up { return .terminateNow }
        guard let st = status() else { return .terminateNow }
        let rec = st["recording"] as? [String: Any]
        let tasks = (st["tasks"] as? [Any])?.count ?? 0
        if rec != nil || tasks > 0 {
            let a = NSAlert()
            a.messageText = rec != nil ? "Folio is recording" : "Folio is still working"
            a.informativeText = rec != nil
                ? "Quitting stops and saves “\(rec?["title"] as? String ?? "the recording")”. It's transcribed next time you open Folio."
                : "\(tasks) task\(tasks == 1 ? " is" : "s are") running or waiting. Transcriptions continue next time; fixes and summaries need starting again."
            a.addButton(withTitle: "Quit")
            a.addButton(withTitle: "Cancel")
            if a.runModal() != .alertFirstButtonReturn { return .terminateCancel }
        }
        quitting = true
        main.showLoading("Quitting…")
        DispatchQueue.global().async {
            _ = engine("quit", post: "{\"force\": false}", timeout: 5)
            for _ in 0..<125 where status() != nil { Thread.sleep(forTimeInterval: 0.2) }   // saving takes a moment
            DispatchQueue.main.async { NSApp.reply(toApplicationShouldTerminate: true) }
        }
        return .terminateLater
    }

    // --- menus

    @objc func showSettings(_ sender: Any?) { if up { main.go("#/settings") } }
    @objc func newRecording(_ sender: Any?) { if up { main.go("#/") } }
    @objc func showActivity(_ sender: Any?) { if up { main.go("#/activity") } }
    @objc func showMain(_ sender: Any?) { if up { main.showInterface() } }
    @objc func openWebsite(_ sender: Any?) { NSWorkspace.shared.open(URL(string: "https://folio.qshlab.com")!) }

    func buildMenu() {
        let bar = NSMenu()
        func menu(_ title: String, _ items: [NSMenuItem]) {
            let top = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            let m = NSMenu(title: title)
            items.forEach(m.addItem)
            top.submenu = m
            bar.addItem(top)
        }
        func item(_ title: String, _ action: Selector?, _ key: String = "", _ mods: NSEvent.ModifierFlags = .command,
                  target: AnyObject? = nil) -> NSMenuItem {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
            i.keyEquivalentModifierMask = mods
            i.target = target
            return i
        }
        menu("Folio", [
            item("About Folio", #selector(NSApplication.orderFrontStandardAboutPanel(_:))),
            .separator(),
            item("Settings…", #selector(showSettings(_:)), ",", target: self),
            .separator(),
            item("Hide Folio", #selector(NSApplication.hide(_:)), "h"),
            item("Hide Others", #selector(NSApplication.hideOtherApplications(_:)), "h", [.command, .option]),
            item("Show All", #selector(NSApplication.unhideAllApplications(_:))),
            .separator(),
            item("Quit Folio", #selector(NSApplication.terminate(_:)), "q"),
        ])
        menu("File", [
            item("New Recording", #selector(newRecording(_:)), "n", target: self),
            .separator(),
            item("Close Window", #selector(NSWindow.performClose(_:)), "w"),
        ])
        menu("Edit", [
            item("Undo", Selector(("undo:")), "z"),
            item("Redo", Selector(("redo:")), "z", [.command, .shift]),
            .separator(),
            item("Cut", #selector(NSText.cut(_:)), "x"),
            item("Copy", #selector(NSText.copy(_:)), "c"),
            item("Paste", #selector(NSText.paste(_:)), "v"),
            item("Select All", #selector(NSText.selectAll(_:)), "a"),
        ])
        menu("View", [
            item("Reload", #selector(MainWindow.reload(_:)), "r", target: main),
            .separator(),
            item("Actual Size", #selector(MainWindow.actualSize(_:)), "0", target: main),
            item("Zoom In", #selector(MainWindow.zoomIn(_:)), "+", target: main),
            item("Zoom Out", #selector(MainWindow.zoomOut(_:)), "-", target: main),
            .separator(),
            item("Activity", #selector(showActivity(_:)), "1", [.command, .option], target: self),
            item("Enter Full Screen", #selector(NSWindow.toggleFullScreen(_:)), "f", [.command, .control]),
        ])
        menu("Window", [
            item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"),
            item("Zoom", #selector(NSWindow.performZoom(_:))),
            .separator(),
            item("Folio", #selector(showMain(_:)), "1", target: self),
        ])
        NSApp.windowsMenu = bar.items.last?.submenu
        menu("Help", [item("Folio Website", #selector(openWebsite(_:)), target: self)])
        NSApp.mainMenu = bar
    }
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
