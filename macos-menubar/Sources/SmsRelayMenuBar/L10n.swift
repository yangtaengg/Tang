import Foundation

func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, bundle: .module, comment: "")
    guard !args.isEmpty else { return format }
    return String(format: format, locale: Locale.current, arguments: args)
}
