import Foundation
import SystemConfiguration
import Darwin

enum LocalNetworkInfo {
    static func defaultIPv4() -> String {
        var wifiAddress: String?
        var wiredAddress: String?
        var privateAddress: String?
        var fallbackAddress: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while ptr != nil {
                defer { ptr = ptr?.pointee.ifa_next }
                guard let interface = ptr?.pointee else { continue }
                guard let nameC = interface.ifa_name else { continue }
                let name = String(cString: nameC)
                let flags = Int32(interface.ifa_flags)
                let isUp = (flags & IFF_UP) == IFF_UP
                let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK
                guard isUp, !isLoopback else { continue }
                guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
                if isIgnoredInterface(name) {
                    continue
                }

                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(
                    interface.ifa_addr,
                    socklen_t(interface.ifa_addr.pointee.sa_len),
                    &host,
                    socklen_t(host.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
                let candidate = String(cString: host)
                if isLinkLocalIPv4(candidate) {
                    continue
                }
                if name == "en0" && isPrivateIPv4(candidate) {
                    wifiAddress = candidate
                    break
                }
                if name.hasPrefix("en") && isPrivateIPv4(candidate) && wiredAddress == nil {
                    wiredAddress = candidate
                }
                if isPrivateIPv4(candidate), privateAddress == nil {
                    privateAddress = candidate
                }
                if fallbackAddress == nil {
                    fallbackAddress = candidate
                }
            }
        }

        if let ifaddr {
            freeifaddrs(ifaddr)
        }

        return wifiAddress ?? wiredAddress ?? privateAddress ?? fallbackAddress ?? "127.0.0.1"
    }

    private static func isIgnoredInterface(_ name: String) -> Bool {
        if name.hasPrefix("utun") || name.hasPrefix("awdl") || name.hasPrefix("llw") {
            return true
        }
        if name.hasPrefix("bridge") || name.hasPrefix("vmnet") || name.hasPrefix("vboxnet") || name.hasPrefix("docker") {
            return true
        }
        return false
    }

    private static func isLinkLocalIPv4(_ ip: String) -> Bool {
        ip.hasPrefix("169.254.")
    }

    private static func isPrivateIPv4(_ ip: String) -> Bool {
        if ip.hasPrefix("10.") || ip.hasPrefix("192.168.") {
            return true
        }
        let parts = ip.split(separator: ".")
        guard parts.count == 4,
              parts[0] == "172",
              let second = Int(parts[1]) else {
            return false
        }
        return (16...31).contains(second)
    }
}
