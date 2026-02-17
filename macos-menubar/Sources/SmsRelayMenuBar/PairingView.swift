import SwiftUI
import AppKit

struct PairingView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Pair device")
                .font(.title2)
                .bold()

            if let device = appState.pairedDeviceName {
                Text("Paired with \(device)")
                    .font(.subheadline)
                    .foregroundStyle(.green)
            } else {
                Text("Waiting for Android authentication")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Text("Mac IP / Host")
            TextField("192.168.0.10", text: $appState.pairingHost)
                .textFieldStyle(.roundedBorder)

            HStack {
                Button("Refresh QR") {
                    appState.refreshPairingQR()
                }
                Button("Regenerate token") {
                    appState.regenerateToken()
                }
                Button("Copy payload") {
                    appState.copyPairingPayload()
                }
            }

            if let qrImage = appState.qrImage {
                Image(nsImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 260, height: 260)
            }

            Text("Token: never expires")
                .font(.footnote)

            Text(appState.qrPayloadString)
                .font(.system(size: 10, weight: .regular, design: .monospaced))
                .lineLimit(5)
        }
        .padding(16)
        .frame(width: 360)
        .onChange(of: appState.pairedDeviceName) { newValue in
            if newValue != nil {
                dismiss()
            }
        }
        .onAppear {
            let app = NSRunningApplication.current
            app.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
            NSApp.activate(ignoringOtherApps: true)
            if let window = NSApp.windows.first(where: { $0.title == "Pair device" }) {
                if window.identifier?.rawValue != "pair-device" {
                    window.identifier = NSUserInterfaceItemIdentifier("pair-device")
                }
                window.makeKeyAndOrderFront(nil)
            }
        }
    }
}
