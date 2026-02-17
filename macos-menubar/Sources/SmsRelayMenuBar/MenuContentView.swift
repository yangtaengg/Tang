import SwiftUI

struct MenuContentView: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var messageStore: MessageStore
    @Environment(\.dismiss) private var dismiss
    @State private var hoveredMessageID: String?

    init(appState: AppState) {
        self.appState = appState
        self._messageStore = ObservedObject(wrappedValue: appState.messageStore)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Tang!")
                    .font(.headline)
                Spacer()
                Text(appState.serverState)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let device = appState.pairedDeviceName {
                Text("Paired: \(device)")
                    .font(.caption)
                    .foregroundStyle(.green)
            } else {
                Text("Not paired")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            Divider()

            if messageStore.messages.isEmpty {
                Text("No messages yet")
                    .foregroundStyle(.secondary)
                    .font(.caption)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messageStore.messages) { message in
                            HStack(spacing: 8) {
                                Button {
                                    appState.openMessageDetail(message)
                                    dismiss()
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text("\(message.timestamp.formatted(date: .omitted, time: .shortened))  \(message.from)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        Text(message.body)
                                            .font(.body)
                                            .lineLimit(2)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(8)
                                    .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                                }
                                .buttonStyle(.plain)

                                Button {
                                    appState.deleteMessage(message)
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundStyle(.red)
                                        .accessibilityLabel("Delete message")
                                }
                                .buttonStyle(.plain)
                                .opacity(hoveredMessageID == message.id ? 1 : 0)
                                .allowsHitTesting(hoveredMessageID == message.id)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onHover { isHovered in
                                hoveredMessageID = isHovered ? message.id : nil
                            }
                        }
                    }
                }
                .frame(maxHeight: 300)
            }
        }
        .padding(12)
        .frame(width: 360)
    }
}
