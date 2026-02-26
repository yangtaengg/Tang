import AppKit
import SwiftUI

struct MessageDetailView: View {
    @ObservedObject var appState: AppState
    let onClose: () -> Void

    @State private var replyText: String = ""
    @State private var requestFocus: Bool = false

    private enum TimelineItem: Identifiable {
        case incoming(SmsMessage)
        case outgoing(AppState.SentReply)

        var id: String {
            switch self {
            case let .incoming(message):
                return "incoming-\(message.id)"
            case let .outgoing(reply):
                return "outgoing-\(reply.id)"
            }
        }

        var timestamp: Date {
            switch self {
            case let .incoming(message):
                return message.timestamp
            case let .outgoing(reply):
                return reply.timestamp
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let message = appState.selectedMessage {
                HStack {
                    Text(L("app_name"))
                        .font(.headline)
                    Spacer()
                    Text(L("message_title"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        let threadMessages = appState.messagesInConversation(for: message)
                        let sentReplies = appState.sentReplies(for: message)
                        let timeline = (threadMessages.map(TimelineItem.incoming) + sentReplies.map(TimelineItem.outgoing))
                            .sorted { $0.timestamp < $1.timestamp }

                        ForEach(timeline) { item in
                            switch item {
                            case let .incoming(threadMessage):
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(threadMessage.timestamp.formatted(date: .omitted, time: .shortened))  \(threadMessage.from)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Text(threadMessage.body)
                                        .font(.body)
                                }
                                .padding(.bottom, 6)
                            case let .outgoing(reply):
                                HStack {
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 3) {
                                        Text(reply.timestamp.formatted(date: .omitted, time: .shortened))
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        Text(reply.text)
                                            .font(.system(size: 12, weight: .semibold))
                                            .multilineTextAlignment(.trailing)
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 7)
                                            .foregroundStyle(reply.status == .sent ? Color.white : Color.primary)
                                            .background(replyBubbleColor(for: reply.status), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    }
                                }
                                .padding(.bottom, 6)
                            }
                        }
                    }
                    .padding(8)
                        .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 260)

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        KeyTextField(
                            placeholder: L("message_reply_placeholder"),
                            text: $replyText,
                            requestFocus: $requestFocus
                        )
                        .frame(height: 24)

                        Button(L("button_clear")) {
                            replyText = ""
                            requestFocus = true
                        }
                        .disabled(replyText.isEmpty)
                        .font(.system(size: 13, weight: .semibold))
                        .controlSize(.large)
                        .buttonStyle(.bordered)
                    }
                    .padding(8)
                    .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

                    if let status = appState.replyStatusText {
                        Text(status)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack {
                    Button(L("button_send")) {
                        appState.sendReplySms(for: message, text: replyText)
                        replyText = ""
                        requestFocus = true
                    }
                    .keyboardShortcut(.defaultAction)
                    .font(.system(size: 13, weight: .semibold))
                    .controlSize(.large)
                    .buttonStyle(.borderedProminent)

                    Button(L("button_copy")) {
                        appState.copy(message)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .controlSize(.large)
                    .buttonStyle(.bordered)

                    if appState.verificationCode(in: message) != nil {
                        Button(L("button_copy_code")) {
                            appState.copyVerificationCode(from: message)
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .controlSize(.large)
                        .buttonStyle(.bordered)
                    }

                    Spacer()

                    Button(L("button_close")) {
                        onClose()
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .controlSize(.large)
                    .buttonStyle(.bordered)
                }
            } else {
                Text(L("message_none_selected"))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(width: 420, height: 360)
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.9))
        .onAppear {
            requestFocus = true
        }
        .onChange(of: appState.selectedMessage?.id) { _ in
            replyText = ""
            requestFocus = true
        }
    }

    private func replyBubbleColor(for status: AppState.SentReply.DeliveryStatus) -> Color {
        switch status {
        case .sending:
            return Color.gray.opacity(0.25)
        case .sent:
            return Color.accentColor
        }
    }
}

private struct KeyTextField: NSViewRepresentable {
    let placeholder: String
    @Binding var text: String
    @Binding var requestFocus: Bool

    final class Coordinator: NSObject, NSTextFieldDelegate {
        @Binding var text: String

        init(text: Binding<String>) {
            _text = text
        }

        func controlTextDidChange(_ obj: Notification) {
            guard let field = obj.object as? NSTextField else {
                return
            }
            text = field.stringValue
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeNSView(context: Context) -> NSTextField {
        let field = NSTextField(string: text)
        field.placeholderString = placeholder
        field.isEditable = true
        field.isSelectable = true
        field.isBezeled = true
        field.bezelStyle = .roundedBezel
        field.delegate = context.coordinator
        return field
    }

    func updateNSView(_ nsView: NSTextField, context: Context) {
        if nsView.stringValue != text {
            nsView.stringValue = text
        }

        guard requestFocus else {
            return
        }

        DispatchQueue.main.async {
            guard requestFocus,
                  let window = nsView.window,
                  window.makeFirstResponder(nsView) else {
                return
            }
            requestFocus = false
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            guard requestFocus,
                  let window = nsView.window,
                  window.makeFirstResponder(nsView) else {
                return
            }
            requestFocus = false
        }
    }
}
