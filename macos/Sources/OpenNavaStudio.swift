import SwiftUI
import AppKit

private let canvas = Color(red: 0.063, green: 0.067, blue: 0.086)
private let tile = Color(red: 0.099, green: 0.099, blue: 0.135)
private let warm = Color(red: 0.912, green: 0.638, blue: 0.426)
private let accent = Color(red: 0.77, green: 0.70, blue: 0.95)
private let dim = Color(red: 0.728, green: 0.719, blue: 0.76)
private let line = Color.white.opacity(0.105)

private struct ActionStyle: ButtonStyle {
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 14).padding(.vertical, 9)
            .foregroundStyle(primary ? Color.black.opacity(0.83) : Color.white.opacity(0.88))
            .background(primary ? accent.opacity(configuration.isPressed ? 0.78 : 1) : Color.white.opacity(configuration.isPressed ? 0.13 : 0.065), in: Capsule())
            .overlay(Capsule().stroke(primary ? .clear : line, lineWidth: 1))
    }
}

private struct RemoteKey: View {
    let icon: String
    let label: String
    let press: () -> Void
    var body: some View {
        Button(action: press) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold)).frame(width: 39, height: 32)
                .background(Color.white.opacity(0.055), in: Capsule())
        }
        .buttonStyle(.plain).foregroundStyle(Color.white.opacity(0.84)).help(label).accessibilityLabel(label)
    }
}

private struct DirectionPad: View {
    let send: (Int) -> Void
    func direction(_ name: String, _ label: String, _ code: Int, _ x: CGFloat, _ y: CGFloat) -> some View {
        Button { send(code) } label: {
            Image(systemName: name).font(.system(size: 17, weight: .medium)).frame(width: 48, height: 40)
                .contentShape(Rectangle())
        }.buttonStyle(.plain).offset(x: x, y: y).accessibilityLabel(label).help(label)
    }
    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [.white.opacity(0.09), .white.opacity(0.025)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(Circle().stroke(line, lineWidth: 1))
            direction("chevron.up", "Up", 19, 0, -53)
            direction("chevron.down", "Down", 20, 0, 53)
            direction("chevron.left", "Left", 21, -53, 0)
            direction("chevron.right", "Right", 22, 53, 0)
            Button { send(23) } label: {
                Text("OK").font(.system(size: 15, weight: .semibold)).foregroundStyle(Color.black.opacity(0.8))
                    .frame(width: 58, height: 58).background(accent.gradient, in: Circle())
            }.buttonStyle(.plain).help("Select").accessibilityLabel("Select")
        }.frame(width: 158, height: 158)
    }
}

private struct ConnectionView: View {
    @ObservedObject var model: Studio
    var body: some View {
        VStack(alignment: .leading, spacing: 19) {
            HStack {
                Text("Connect a TV").font(.system(size: 24, weight: .semibold))
                Spacer()
                Button { model.sheet = false } label: { Image(systemName: "xmark.circle.fill").font(.title2) }
                    .buttonStyle(.plain).foregroundStyle(dim).accessibilityLabel("Close")
            }
            Picker("Connection type", selection: Binding(get: { model.tab }, set: { choice in
                model.tab = choice
                model.connectionMessage = ""
                model.pairCode = ""
            })) {
                Text("Connect").tag(ConnectionTab.connect)
                Text("Tailscale").tag(ConnectionTab.tailscale)
                Text("Pair with a code").tag(ConnectionTab.pair)
            }.pickerStyle(.segmented).labelsHidden().disabled(model.connecting)
            if model.tab == .tailscale {
                VStack(alignment: .leading, spacing: 7) {
                    Text("Connect over Tailscale").font(.headline)
                    Text("Connect the Mac and TV to the same tailnet. Find the TV’s address in Tailscale.")
                        .font(.callout).foregroundStyle(dim).fixedSize(horizontal: false, vertical: true)
                }
                TextField("Tailscale IP or device name", text: $model.tailHost)
                if model.tailPairing {
                    Text("On the TV, choose Pair device with pairing code. Enter the port and code shown there.")
                        .font(.callout).foregroundStyle(dim).fixedSize(horizontal: false, vertical: true)
                    TextField("Pairing port", text: $model.tailPairPort)
                    SecureField("Six-digit code", text: $model.pairCode)
                } else {
                    Text("On Google TV, find the connection port under Wireless debugging → IP address and port. Fire TV usually uses 5555.")
                        .font(.callout).foregroundStyle(dim).fixedSize(horizontal: false, vertical: true)
                    TextField("Connection port", text: $model.tailPort)
                }
                Button(model.tailPairing ? "Already paired? Connect to the TV" : "Pair this Mac with Google TV") {
                    model.tailPairing.toggle()
                    model.pairCode = ""
                    model.tailPairPort = ""
                    model.connectionMessage = ""
                }.buttonStyle(.link).disabled(model.connecting)
            } else if model.pairing {
                Text("On Google TV, open Developer options → Wireless debugging → Pair device with pairing code.")
                    .font(.callout).foregroundStyle(dim).fixedSize(horizontal: false, vertical: true)
                TextField("Pairing address and port", text: $model.pairAddress)
                SecureField("Six-digit code", text: $model.pairCode)
            } else {
                Text("Enter the Fire TV’s IP address, or the IP address and connection port shown under Wireless debugging on Google TV.")
                    .font(.callout).foregroundStyle(dim).fixedSize(horizontal: false, vertical: true)
                TextField("IP address or host:port", text: $model.connectAddress)
            }
            let matching = model.discoveries.filter { model.tab != .tailscale && $0.pairing == model.pairing }
            if !matching.isEmpty {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Discovered on your network").font(.caption.weight(.medium)).foregroundStyle(dim)
                    ForEach(matching.prefix(4)) { device in
                        Button { if model.pairing { model.pairAddress = device.endpoint } else { model.connectAddress = device.endpoint } } label: {
                            Label(device.endpoint, systemImage: "wifi").frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.borderless)
                    }
                }
            }
            if model.tab != .tailscale && !model.devices.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Connected devices").font(.caption).foregroundStyle(dim)
                    ForEach(model.devices) { device in
                        Button(device.name + "  ·  " + device.id) { model.choose(device.id); model.sheet = false }
                            .buttonStyle(.borderless)
                    }
                }
            }
            if model.tab != .tailscale && !model.locked.isEmpty {
                Text("Approve the debugging request on the TV.").foregroundStyle(warm).font(.callout)
            }
            if !model.connectionMessage.isEmpty {
                Text(model.connectionMessage).foregroundStyle(accent).font(.callout).textSelection(.enabled)
            }
            HStack {
                if model.tab != .tailscale {
                    Button("Find devices") { Task { await model.refresh() } }.buttonStyle(.borderless)
                        .disabled(model.refreshing || model.connecting)
                }
                Spacer()
                Button(model.pairing ? "Pair" : "Connect") { Task { await model.connect() } }
                    .buttonStyle(ActionStyle(primary: true)).keyboardShortcut(.defaultAction).disabled(model.connecting)
            }
        }
        .textFieldStyle(.roundedBorder).padding(26).frame(width: 445)
        .background(canvas).preferredColorScheme(.dark).interactiveDismissDisabled(model.connecting)
    }
}

private struct StudioView: View {
    @ObservedObject var model: Studio
    private var title: String { model.selection?.name ?? "Select a TV" }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 13) {
                Image(systemName: "display.2").font(.system(size: 19, weight: .medium)).foregroundStyle(accent)
                    .frame(width: 39, height: 39).background(accent.opacity(0.105), in: RoundedRectangle(cornerRadius: 13))
                Text("OpenNava Studio").font(.system(size: 23, weight: .semibold)).tracking(-0.7)
                Spacer()
                if !model.devices.isEmpty {
                    Menu {
                        ForEach(model.devices) { device in
                            Button { model.choose(device.id) } label: {
                                if device.id == model.selectedID { Label(device.name, systemImage: "checkmark") }
                                else { Text(device.name + " · " + device.id) }
                            }
                        }
                        Divider()
                        Button("Refresh devices") { Task { await model.refresh() } }
                    } label: {
                        HStack(spacing: 7) { Circle().fill(accent).frame(width: 7, height: 7); Text(title); Image(systemName: "chevron.down").font(.caption2) }
                            .padding(.horizontal, 13).padding(.vertical, 8).background(Color.white.opacity(0.055), in: Capsule())
                    }.menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().disabled(model.installing).accessibilityLabel("Select device")
                }
                Button { model.openConnect() } label: {
                    Image(systemName: "plus").font(.system(size: 15, weight: .semibold)).frame(width: 30, height: 30)
                        .overlay(Circle().stroke(line))
                }.buttonStyle(.plain).help("Connect a TV").accessibilityLabel("Connect a TV")
            }
            .padding(.horizontal, 30).padding(.vertical, 17)
            Rectangle().fill(line).frame(height: 1)
            HStack(alignment: .top, spacing: 24) {
                VStack(alignment: .leading, spacing: 15) {
                    HStack {
                        Text("TV screen").font(.system(size: 27, weight: .medium)).tracking(-1.1)
                        Spacer()
                        if model.selection != nil {
                            Button { Task { await model.preview() } } label: { Image(systemName: "arrow.clockwise") }
                                .buttonStyle(.plain).foregroundStyle(dim).help("Refresh preview").accessibilityLabel("Refresh preview")
                        }
                    }
                    GeometryReader { bounds in
                        ZStack {
                            Color.black.opacity(0.7)
                        if let image = model.image {
                            Image(nsImage: image).resizable().scaledToFit()
                                .frame(width: bounds.size.width, height: bounds.size.height)
                        } else if model.selection != nil {
                            Image(systemName: "tv").font(.system(size: 38, weight: .thin)).foregroundStyle(dim)
                        } else {
                            VStack(spacing: 16) {
                                Image(systemName: "tv.and.hifispeaker.fill").font(.system(size: 35, weight: .light)).foregroundStyle(accent)
                                Button("Connect a TV") { model.openConnect() }.buttonStyle(ActionStyle())
                            }
                        }
                        }.frame(width: bounds.size.width, height: bounds.size.height)
                    }.frame(minHeight: 165, idealHeight: 310, maxHeight: 410)
                    .clipShape(RoundedRectangle(cornerRadius: 15)).overlay(RoundedRectangle(cornerRadius: 15).stroke(line))
                    HStack(spacing: 9) {
                        Button { if model.live { model.stopMirror() } else { model.openMirror() } } label: {
                            Label(model.live ? "Close live view" : "Open live view", systemImage: model.live ? "stop.fill" : "play.fill")
                        }.buttonStyle(ActionStyle(primary: true)).disabled(model.selection == nil || model.installing)
                        Spacer()
                        if let date = model.imageDate {
                            HStack(spacing: 4) { Text("Preview"); Text(date, style: .time).monospacedDigit() }
                                .font(.caption).foregroundStyle(dim)
                        }
                    }
                    if model.live { Text("Use the arrow keys and Return in the live window.").font(.callout).foregroundStyle(dim) }
                }
                VStack(spacing: 0) {
                    Text("Remote").font(.system(size: 18, weight: .semibold)).padding(.top, 18)
                    Spacer(minLength: 15)
                    DirectionPad(send: model.send)
                    Spacer(minLength: 15)
                    HStack(spacing: 6) {
                        RemoteKey(icon: "arrow.uturn.backward", label: "Back") { model.send(4) }
                        RemoteKey(icon: "house", label: "Home") { model.send(3) }
                        RemoteKey(icon: "playpause.fill", label: "Play or pause") { model.send(85) }
                    }.padding(.bottom, 20)
                }.frame(width: 202).frame(minHeight: 285, maxHeight: .infinity)
                .background(tile, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(line))
                .disabled(model.selection == nil || model.installing).opacity(model.selection == nil ? 0.48 : 1)
            }.padding(.horizontal, 30).padding(.vertical, 22)
            Spacer(minLength: 0)
            if !model.message.isEmpty {
                Text(model.message).font(.callout).foregroundStyle(accent).textSelection(.enabled)
                    .padding(.horizontal, 30).padding(.bottom, 12)
            }
            Rectangle().fill(line).frame(height: 1)
            HStack(spacing: 9) {
                Image(systemName: model.selection == nil ? "antenna.radiowaves.left.and.right" : "cable.connector.horizontal").foregroundStyle(dim)
                Text(model.selection?.id ?? (model.refreshing ? "Finding devices…" : "No TV connected")).font(.system(size: 12, design: .monospaced)).foregroundStyle(dim)
                Spacer()
                if let target = model.selection, let build = TVBuild.embedded {
                    if (target.installedCode ?? 0) < build.code {
                        Button(target.installedCode != nil ? "Update OpenNava…" : "Install OpenNava…") { model.confirmUpdate = true }
                            .buttonStyle(.borderless).disabled(model.installing).foregroundStyle(warm)
                    } else if let version = target.installedName {
                        Text("OpenNava " + version).font(.caption).foregroundStyle(dim)
                    }
                }
            }.padding(.horizontal, 30).padding(.vertical, 13)
        }
        .background(canvas).foregroundStyle(.white).preferredColorScheme(.dark)
        .frame(minWidth: 770, idealWidth: 890, minHeight: 480, idealHeight: 580)
        .sheet(isPresented: $model.sheet) { ConnectionView(model: model) }
        .alert(model.selection?.installedCode == nil ? "Install OpenNava?" : "Update OpenNava?", isPresented: $model.confirmUpdate) {
            Button("Cancel", role: .cancel) {}
            Button("Install and open") { Task { await model.install() } }
        } message: {
            let build = TVBuild.embedded?.version ?? ""
            Text("Install OpenNava " + build + " on " + title + " and open it on the TV?")
        }
        .task { await model.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            if !model.sheet && !model.installing { Task { await model.refresh() } }
        }
    }
}

@MainActor
final class AppLifecycle: NSObject, NSApplicationDelegate {
    let studio = Studio()
    private var eventMonitor: Any?
    func applicationDidFinishLaunching(_ notification: Notification) {
        eventMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, !self.studio.sheet, !self.studio.confirmUpdate,
                  self.studio.selection != nil, NSApp.keyWindow?.title == "OpenNava Studio",
                  NSApp.keyWindow?.attachedSheet == nil,
                  event.modifierFlags.intersection([.command, .control, .option]).isEmpty else { return event }
            let keys: [UInt16: Int] = [123: 21, 124: 22, 125: 20, 126: 19, 36: 23, 76: 23, 53: 4]
            guard let key = keys[event.keyCode] else { return event }
            if !event.isARepeat { self.studio.send(key) }
            return nil
        }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) {
        if let eventMonitor { NSEvent.removeMonitor(eventMonitor) }
        studio.stopMirror()
    }
}

@main
struct OpenNavaStudio: App {
    @NSApplicationDelegateAdaptor(AppLifecycle.self) var lifecycle
    var body: some Scene {
        Window("OpenNava Studio", id: "studio") {
            StudioView(model: lifecycle.studio)
        }.windowResizability(.contentMinSize).defaultSize(width: 890, height: 580)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Connect a TV …") { lifecycle.studio.openConnect() }.keyboardShortcut("k")
                Button("Refresh devices") { Task { await lifecycle.studio.refresh() } }.keyboardShortcut("r")
                Button("Open live view") { lifecycle.studio.openMirror() }.keyboardShortcut("l")
            }
        }
    }
}
