import AppKit
import SwiftUI

struct MessageDetailView: View {
    @ObservedObject var appState: AppState
    let onClose: () -> Void

    @State private var replyText: String = ""
    @State private var requestFocus: Bool = false

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

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        KeyTextField(
                            placeholder: "Type a reply...",
                            text: $replyText,
                            requestFocus: $requestFocus
                        )
                        .frame(height: 24)

                        Button("Clear") {
                            replyText = ""
                            requestFocus = true
                        }
                        .disabled(replyText.isEmpty)
                    }

                    if let status = appState.replyStatusText {
                        Text(status)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack {
                    Button("Send") {
                        appState.sendReplySms(for: message, text: replyText)
                        replyText = ""
                        requestFocus = true
                    }
                    .keyboardShortcut(.return, modifiers: [.command])

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
                        onClose()
                    }
                }
            } else {
                Text("No message selected")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(width: 420, height: 360)
        .onAppear {
            requestFocus = true
        }
        .onChange(of: appState.selectedMessage?.id) { _ in
            replyText = ""
            requestFocus = true
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

        if let window = nsView.window, window.makeFirstResponder(nsView) {
            DispatchQueue.main.async {
                requestFocus = false
            }
            return
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
