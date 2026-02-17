import SwiftUI

struct MessageDetailView: View {
    @ObservedObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let message = appState.selectedMessage {
                Text(message.from)
                    .font(.title3)
                    .bold()

                Text(message.timestamp.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                ScrollView {
                    Text(message.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 260)

                HStack {
                    Button("Copy") {
                        appState.copy(message)
                    }
                    if appState.verificationCode(in: message) != nil {
                        Button("인증번호 복사") {
                            appState.copyVerificationCode(from: message)
                        }
                    }
                    Spacer()
                    Button("Close") {
                        appState.closeMessageDetailWindow()
                    }
                }
            } else {
                Text("No message selected")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(width: 420, height: 360)
    }
}
