import SwiftUI
import AppKit

struct PairingView: View {
    @ObservedObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("app_name"))
                    .font(.headline)
                Spacer()
                Text(L("pair_device_title"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let device = appState.pairedDeviceName {
                Text(L("menu_paired_device", device))
                    .font(.caption)
                    .foregroundStyle(.green)
            } else {
                Text(L("pair_waiting_auth"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(L("pair_code_title"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(appState.pairingCode)
                    .font(.system(size: 20, weight: .bold, design: .monospaced))
                    .textSelection(.enabled)

                Text(L("pair_host_autodetect"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(10)
            .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

            HStack {
                Button(L("pair_refresh_qr")) {
                    appState.refreshPairingQR()
                }
                .font(.system(size: 13, weight: .semibold))
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                Button(L("pair_regenerate_token")) {
                    appState.regenerateToken()
                }
                .font(.system(size: 13, weight: .semibold))
                .controlSize(.large)
                .buttonStyle(.bordered)
                Button(L("pair_copy_payload")) {
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
