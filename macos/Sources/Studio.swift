import SwiftUI
import AppKit

enum ConnectionTab { case connect, tailscale, pair }

@MainActor
final class Studio: ObservableObject {
    @Published var devices: [Television] = []
    @Published var discoveries: [Discovery] = []
    @Published var locked: [String] = []
    @Published var selectedID = ""
    @Published var refreshing = false
    @Published var connecting = false
    @Published var installing = false
    @Published var live = false
    @Published var image: NSImage?
    @Published var imageDate: Date?
    @Published var message = ""
    @Published var sheet = false
    @Published var confirmUpdate = false
    @Published var connectAddress = ""
    @Published var pairAddress = ""
    @Published var pairCode = ""
    @Published var tab: ConnectionTab = .connect
    @Published var tailHost = UserDefaults.standard.string(forKey: "tailHost") ?? ""
    @Published var tailPort = UserDefaults.standard.string(forKey: "tailPort") ?? ""
    @Published var tailPairing = false
    @Published var tailPairPort = ""
    var pairing: Bool { tab == .pair || (tab == .tailscale && tailPairing) }
    @Published var connectionMessage = ""
    private var mirror: Process?
    private var imageRevision = 0
    private var keySequence: Task<Void, Never>?
    private var delayedPreview: Task<Void, Never>?
    var selection: Television? { devices.first { $0.id == selectedID } }

    func refresh(prefer: String? = nil) async {
        guard !refreshing else { return }
        refreshing = true
        let (found, nearby, untrusted, error) = await TVClient.inventory()
        devices = found
        discoveries = nearby
        locked = untrusted
        let wanted = prefer ?? (selectedID.isEmpty ? UserDefaults.standard.string(forKey: "lastDevice") : selectedID)
        let next = found.first { $0.id == wanted } ?? (found.count == 1 ? found.first : nil)
        let changed = selectedID != (next?.id ?? "")
        if changed { choose(next?.id ?? "") }
        if let error { message = "Could not find devices. " + String(error.prefix(140)) }
        if !changed, next != nil, image == nil { Task { await preview() } }
        refreshing = false
    }
    func choose(_ id: String) {
        if selectedID != id { stopMirror(); delayedPreview?.cancel() }
        selectedID = id
        imageRevision += 1
        image = nil
        imageDate = nil
        message = ""
        if !id.isEmpty {
            UserDefaults.standard.set(id, forKey: "lastDevice")
            Task { await preview() }
        }
    }
    func preview() async {
        guard let serial = selection?.id else { return }
        imageRevision += 1
        let revision = imageRevision
        let frame = await Tools.run(["-s", serial, "exec-out", "screencap", "-p"], timeout: 9)
        guard selectedID == serial, revision == imageRevision else { return }
        if frame.code == 0, frame.bytes.starts(with: [137,80,78,71]), let shot = NSImage(data: frame.bytes) {
            image = shot
            imageDate = Date()
        } else if image == nil {
            message = "Preview unavailable. You can still try the live view."
        }
    }
    func openConnect(_ item: Discovery? = nil) {
        connectionMessage = ""
        pairCode = ""
        tab = item?.pairing == true ? .pair : (item == nil && !tailHost.isEmpty ? .tailscale : .connect)
        tailPairing = false
        tailPairPort = ""
        if let item {
            if item.pairing { pairAddress = item.endpoint }
            else { connectAddress = item.endpoint }
        }
        sheet = true
    }
    func connect() async {
        guard !connecting else { return }
        let pair = pairing
        let viaTailnet = tab == .tailscale
        let host = tailHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = (pair ? tailPairPort : tailPort).trimmingCharacters(in: .whitespacesAndNewlines)
        let target = viaTailnet ? TVClient.endpoint(host: host, port: port)
                                : TVClient.endpoint(pair ? pairAddress : connectAddress, requirePort: pair)
        guard let target else {
            connectionMessage = viaTailnet ? "Enter the TV address and a port between 1 and 65535."
                : pair ? "Enter the IP address and pairing port." : "Enter the TV address, such as tv.local:5555."
            return
        }
        let code = pairCode.trimmingCharacters(in: .whitespacesAndNewlines)
        if pair && code.range(of: "^[0-9]{6}$", options: .regularExpression) == nil {
            connectionMessage = "Enter the six-digit pairing code shown on the TV."
            return
        }
        connecting = true
        connectionMessage = pair ? "Pairing…" : "Waiting for the TV…"
        defer { connecting = false }
        let result = await Tools.run([pair ? "pair" : "connect", target], input: pair ? code : nil, timeout: 20)
        guard result.code == 0 && (pair ? result.text.lowercased().contains("successfully paired") : result.text.contains("connected to")) else {
            connectionMessage = TVClient.failure(result)
            if viaTailnet && !result.text.localizedCaseInsensitiveContains("authenticate") {
                connectionMessage += " Check that both devices appear online in Tailscale and that the TV still shows the same port."
            }
            return
        }
        if pair {
            pairCode = ""
            // mDNS auto-connect may complete a moment after the pairing service ends.
            try? await Task.sleep(nanoseconds: 700_000_000)
        }
        await refresh(prefer: target)
        if let matched = devices.first(where: { $0.id == target }) {
            if viaTailnet && !pair {
                UserDefaults.standard.set(host, forKey: "tailHost")
                UserDefaults.standard.set(port, forKey: "tailPort")
            }
            choose(matched.id)
            sheet = false
            return
        }
        if pair {
            if viaTailnet {
                tailPairing = false
                tailPairPort = ""
                connectionMessage = "Paired. Go back one screen on the TV and enter the port beside IP address and port."
                return
            }
            tab = .connect
            connectAddress = ""
            connectionMessage = "Paired. Enter the address and connection port from Wireless debugging or select the TV in the device list."
        } else {
            connectionMessage = "Approve the connection on the TV, then choose Find devices."
        }
    }
    func send(_ code: Int) {
        guard let device = selection, !installing else { return }
        delayedPreview?.cancel()
        let previous = keySequence
        keySequence = Task {
            await previous?.value
            guard selectedID == device.id else { return }
            let result = await Tools.shell(device.id, ["input", "keyevent", String(code)])
            if result.code != 0 { message = TVClient.failure(result) }
            if !live && result.code == 0 {
                delayedPreview?.cancel()
                delayedPreview = Task {
                    do { try await Task.sleep(nanoseconds: 350_000_000) } catch { return }
                    if selectedID == device.id { await preview() }
                }
            }
        }
    }
    func openMirror() {
        guard mirror == nil, let device = selection else { return }
        let proc = Process(), output = Pipe()
        guard let scrcpy = Tools.scrcpy else {
            message = "Install scrcpy on this Mac, then relaunch OpenNava Studio."
            return
        }
        proc.executableURL = scrcpy
        proc.environment = Tools.environment
        proc.arguments = ["-s", device.id, "--no-audio", "--max-size=1600", "--max-fps=45", "--window-width=1000", "--window-title=OpenNava Studio · " + device.name]
        proc.standardOutput = output
        proc.standardError = output
        // Read continuous logs so scrcpy can never block on a full pipe.
        final class LogTail: @unchecked Sendable {
            private let lock = NSLock()
            private var data = Data()
            func append(_ chunk: Data) { lock.lock(); defer { lock.unlock() }; data.append(chunk); if data.count > 16_384 { data.removeFirst(data.count - 16_384) } }
            func lastError() -> String? {
                lock.lock(); defer { lock.unlock() }
                return String(decoding: data, as: UTF8.self).split(separator: "\n").last(where: { $0.contains("ERROR") }).map { String($0.prefix(190)) }
            }
        }
        let log = LogTail()
        output.fileHandleForReading.readabilityHandler = { handle in
            let chunk = handle.availableData
            if chunk.isEmpty { handle.readabilityHandler = nil } else { log.append(chunk) }
        }
        proc.terminationHandler = { [weak self] finished in
            output.fileHandleForReading.readabilityHandler = nil
            Task { @MainActor in
                guard let self, self.mirror === finished else { return }
                self.mirror = nil
                self.live = false
                if finished.terminationStatus != 0 {
                    self.message = log.lastError() ?? "Live view disconnected. Check the TV connection."
                }
                await self.preview()
            }
        }
        do {
            try proc.run()
            mirror = proc
            live = true
            message = ""
        } catch {
            output.fileHandleForReading.readabilityHandler = nil
            message = "Could not start scrcpy. " + error.localizedDescription
        }
    }
    func stopMirror() {
        if let proc = mirror, proc.isRunning { proc.terminate() }
        mirror = nil
        live = false
    }
    func install() async {
        guard !installing, let selected = selection, let build = TVBuild.embedded else { return }
        installing = true
        defer { installing = false }
        message = "Checking the TV…"
        guard let now = await TVClient.inspect(selected.id), !now.hardware.isEmpty, now.hardware != "null",
              now.hardware == selected.hardware,
              now.installedCode.map({ $0 < build.code }) ?? true else {
            message = "The TV or installed version has changed. Refresh the device list."
            return
        }
        stopMirror()
        message = "OpenNava " + build.version + " is being transferred…"
        let result = await Tools.run(["-s", selected.id, "install", "-r", build.apk.path], timeout: 120)
        guard result.code == 0, result.text.components(separatedBy: .newlines).contains(where: { $0.trimmingCharacters(in: .whitespaces) == "Success" }) else {
            message = "Installation failed. " + TVClient.failure(result)
            return
        }
        guard let verified = await TVClient.inspect(selected.id), verified.hardware == now.hardware, verified.installedCode == build.code else {
            message = "Transfer finished. Reconnect to confirm the installed version."
            return
        }
        await refresh(prefer: selected.id)
        let launch = await Tools.shell(selected.id, ["am", "start", "-W", "-a", "android.intent.action.MAIN",
                                                          "-c", "android.intent.category.LEANBACK_LAUNCHER", "-n", TVBuild.entry])
        if launch.code == 0 && !launch.text.contains("Error") {
            message = "OpenNava " + build.version + " is installed and running."
        } else {
            message = "OpenNava " + build.version + " is installed. Open it on the TV."
        }
        await preview()
    }
}
