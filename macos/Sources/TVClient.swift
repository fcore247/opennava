import Foundation
import AppKit
import Darwin

struct ToolResult {
    let bytes: Data
    let code: Int32
    var text: String { String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) }
}

enum Tools {
    // Finder does not inherit a Terminal login shell's PATH.
    private static let paths: [String] = {
        let environment = ProcessInfo.processInfo.environment
        let manager = FileManager.default
        let sdk = [environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"],
                   manager.homeDirectoryForCurrentUser.appendingPathComponent("Library/Android/sdk").path]
            .compactMap { $0 }.map { $0 + "/platform-tools" }
        return (environment["PATH"] ?? "").split(separator: ":").map(String.init) + sdk
            + ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"]
    }()
    private static func executable(_ name: String, override: String) -> URL? {
        if let path = ProcessInfo.processInfo.environment[override] {
            return FileManager.default.isExecutableFile(atPath: path) ? URL(fileURLWithPath: path) : nil
        }
        return paths.map { URL(fileURLWithPath: $0).appendingPathComponent(name) }
            .first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }
    static let adb = executable("adb", override: "OPENNAVA_ADB")
    static let scrcpy = executable("scrcpy", override: "OPENNAVA_SCRCPY")
    static var environment: [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["ADB"] = adb?.path
        env["PATH"] = paths.joined(separator: ":")
        return env
    }
    static func run(_ args: [String], input: String? = nil, timeout: TimeInterval = 10) async -> ToolResult {
        await Task.detached(priority: .userInitiated) { () -> ToolResult in
            guard let adb else {
                return ToolResult(bytes: Data("Install Android Platform Tools, then relaunch OpenNava Studio.".utf8), code: -1)
            }
            let proc = Process(), output = Pipe(), stdin = Pipe()
            proc.executableURL = adb
            proc.arguments = args
            proc.environment = environment
            proc.standardOutput = output
            proc.standardError = output
            proc.standardInput = stdin
            do { try proc.run() }
            catch { return ToolResult(bytes: Data(error.localizedDescription.utf8), code: -1) }
            if let input { try? stdin.fileHandleForWriting.write(contentsOf: Data((input + "\n").utf8)) }
            try? stdin.fileHandleForWriting.close()
            let deadline = DispatchWorkItem {
                guard proc.isRunning else { return }
                proc.terminate()
                DispatchQueue.global().asyncAfter(deadline: .now() + 0.7) {
                    if proc.isRunning { kill(proc.processIdentifier, SIGKILL) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: deadline)
            let data = output.fileHandleForReading.readDataToEndOfFile()
            proc.waitUntilExit()
            deadline.cancel()
            return ToolResult(bytes: data, code: proc.terminationStatus)
        }.value
    }
    static func shell(_ serial: String, _ args: [String]) async -> ToolResult {
        await run(["-s", serial, "shell"] + args)
    }
}

struct Television: Identifiable, Equatable {
    let id: String
    let name: String
    let manufacturer: String
    let hardware: String
    let installedCode: Int?
    let installedName: String?
    var isFire: Bool { manufacturer.localizedCaseInsensitiveContains("amazon") }
}

struct Discovery: Identifiable, Equatable {
    let id: String
    let kind: String
    let endpoint: String
    var pairing: Bool { kind.hasPrefix("_adb-tls-pairing.") }
}

struct TVBuild {
    let apk: URL
    let version: String
    let code: Int
    static let package = "site.navatv.launcher"
    static let entry = "site.navatv.launcher/.MainActivity"
    static let embedded: TVBuild? = {
        guard let plist = Bundle.main.url(forResource: "TVBuild", withExtension: "plist"),
              let values = NSDictionary(contentsOf: plist),
              let version = values["versionName"] as? String,
              let code = values["versionCode"] as? Int,
              values["package"] as? String == package,
              let apk = Bundle.main.url(forResource: "OpenNava", withExtension: "apk") else { return nil }
        return TVBuild(apk: apk, version: version, code: code)
    }()
}

enum TVClient {
    static func capture(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let match = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }
    static func inspect(_ serial: String) async -> Television? {
        // Limit work to the selected transport; other attached Android devices may be phones.
        let props = await Tools.shell(serial, ["getprop"])
        guard props.code == 0 else { return nil }
        func prop(_ key: String) -> String {
            capture("(?m)^\\[" + NSRegularExpression.escapedPattern(for: key) + "\\]: \\[(.*?)\\]$", in: props.text) ?? ""
        }
        let manufacturer = prop("ro.product.manufacturer"), model = prop("ro.product.model")
        if !prop("ro.build.characteristics").contains("tv") {
            let feature = await Tools.shell(serial, ["pm", "has-feature", "android.software.leanback"])
            guard feature.text == "true" else { return nil }
        }
        async let info = Tools.shell(serial, ["dumpsys", "package", TVBuild.package])
        let version = await info.text
        let name: String
        switch model {
        case "AFTKRT": name = "Fire TV Stick 4K Max"
        case "Google TV Streamer": name = "Google TV Streamer"
        default: name = model.isEmpty ? "Android TV" : model
        }
        let hardware = prop("ro.serialno").isEmpty
            ? await Tools.shell(serial, ["settings", "get", "secure", "android_id"]).text : prop("ro.serialno")
        return Television(id: serial, name: name, manufacturer: manufacturer, hardware: hardware,
                          installedCode: capture("versionCode=(\\d+)", in: version).flatMap(Int.init),
                          installedName: capture("versionName=([^\\s]+)", in: version))
    }
    static func inventory() async -> ([Television], [Discovery], [String], String?) {
        async let listing = Tools.run(["devices", "-l"])
        async let mdns = Tools.run(["mdns", "services"], timeout: 5)
        let list = await listing
        guard list.code == 0 else { return ([], [], [], list.text) }
        let rows = list.text.components(separatedBy: .newlines).map { $0.split(whereSeparator: { $0.isWhitespace }).map(String.init) }
        let serials = rows.compactMap { $0.count > 1 && $0[1] == "device" ? $0[0] : nil }
        let locked = rows.compactMap { $0.count > 1 && $0[1] == "unauthorized" ? $0[0] : nil }
        let televisions = await withTaskGroup(of: Television?.self) { group in
            for serial in serials { group.addTask { await inspect(serial) } }
            var devices = [Television]()
            for await device in group { if let device { devices.append(device) } }
            return devices.sorted { $0.name == $1.name ? $0.id < $1.id : $0.name < $1.name }
        }
        let seen = await mdns.text.components(separatedBy: .newlines).compactMap { line -> Discovery? in
            let cols = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            guard cols.count == 3, cols[1].hasPrefix("_adb"), endpoint(cols[2], requirePort: true) != nil,
                  !serials.contains(cols[2]), !serials.contains(where: { $0 == cols[0] + "." + cols[1] }) else { return nil }
            return Discovery(id: cols[0] + cols[1], kind: cols[1], endpoint: cols[2])
        }
        return (televisions, seen, locked, nil)
    }
    static func endpoint(host: String, port: String) -> String? {
        var host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = port.trimmingCharacters(in: .whitespacesAndNewlines)
        guard port.range(of: #"^[0-9]{1,5}$"#, options: .regularExpression) != nil,
              let number = Int(port), (1...65535).contains(number) else { return nil }
        let bracketed = host.hasPrefix("[") && host.hasSuffix("]")
        if bracketed { host = String(host.dropFirst().dropLast()) }
        if host.contains(":") {
            let address = host.components(separatedBy: "%")
            guard address.count <= 2 else { return nil }
            if address.count == 2, address[1].range(of: #"^[a-zA-Z0-9_.-]{1,32}$"#, options: .regularExpression) == nil { return nil }
            var binary = in6_addr()
            guard inet_pton(AF_INET6, address[0], &binary) == 1 else { return nil }
            return "[\(host)]:\(number)"
        }
        guard !bracketed else { return nil }
        // A full MagicDNS name may be copied with its final DNS root dot.
        if host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty, host.utf8.count <= 253 else { return nil }
        if host.contains(".") && host.allSatisfy({ "0123456789.".contains($0) }) {
            var binary = in_addr()
            guard inet_pton(AF_INET, host, &binary) == 1 else { return nil }
        } else {
            for label in host.components(separatedBy: ".") {
                guard label.range(of: #"^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"#, options: .regularExpression) != nil else { return nil }
            }
        }
        return "\(host):\(number)"
    }

    static func endpoint(_ input: String, requirePort: Bool) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("["), let end = text.firstIndex(of: "]") {
            let host = String(text[...end]), suffix = text[text.index(after: end)...]
            if suffix.isEmpty { return requirePort ? nil : endpoint(host: host, port: "5555") }
            guard suffix.first == ":" else { return nil }
            return endpoint(host: host, port: String(suffix.dropFirst()))
        }
        if text.filter({ $0 == ":" }).count == 1, let split = text.firstIndex(of: ":") {
            return endpoint(host: String(text[..<split]), port: String(text[text.index(after: split)...]))
        }
        return requirePort ? nil : endpoint(host: text, port: "5555")
    }
    static func failure(_ result: ToolResult) -> String {
        let source = result.text
        if source.contains("unauthorized") || source.contains("authenticate") {
            return "Approve the debugging request on the TV."
        }
        if source.contains("refused") || source.contains("timed out") || source.contains("unreachable") || source.isEmpty {
            return "The TV did not respond. Check its address, port and wireless debugging settings."
        }
        return String(source.split(separator: "\n").last?.prefix(185) ?? "Connection failed.")
    }
}
