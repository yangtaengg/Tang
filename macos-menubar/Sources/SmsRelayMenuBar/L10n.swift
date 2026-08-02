import Foundation

func L(_ key: String, _ args: CVarArg...) -> String {
    let bundle: Bundle = Bundle.main.bundleURL.pathExtension == "app" ? .main : .module
    let format = NSLocalizedString(key, bundle: bundle, comment: "")
    guard !args.isEmpty else { return format }
    return String(format: format, locale: Locale.current, arguments: args)
}
