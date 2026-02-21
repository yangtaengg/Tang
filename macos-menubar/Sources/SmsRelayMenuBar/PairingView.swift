import SwiftUI
import AppKit

struct PairingView: View {
    @ObservedObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Tang!")
                    .font(.headline)
                Spacer()
                Text("Pair device")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let device = appState.pairedDeviceName {
                Text("Paired: \(device)")
                    .font(.caption)
                    .foregroundStyle(.green)
            } else {
                Text("Waiting for Android authentication")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("6-digit Pair Code")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(appState.pairingCode)
                    .font(.system(size: 20, weight: .bold, design: .monospaced))
                    .textSelection(.enabled)

                Text("Host is auto-detected from current Wi-Fi network.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(10)
            .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

            HStack {
                Button("Refresh QR") {
                    appState.refreshPairingQR()
                }
                .font(.system(size: 13, weight: .semibold))
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                Button("Regenerate token") {
                    appState.regenerateToken()
                }
                .font(.system(size: 13, weight: .semibold))
                .controlSize(.large)
                .buttonStyle(.bordered)
                Button("Copy payload") {
                    appState.copyPairingPayload()
                }
                .font(.system(size: 13, weight: .semibold))
                .controlSize(.large)
                .buttonStyle(.bordered)
            }

            if let qrImage = appState.qrImage {
                HStack {
                    Spacer()
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 260, height: 260)
                    Spacer()
                }
                .padding(8)
                .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding(16)
        .frame(width: 360)
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.9))
    }
}
