import Foundation
import SystemConfiguration
import Darwin

enum LocalNetworkInfo {
    static func defaultIPv4() -> String {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while ptr != nil {
                defer { ptr = ptr?.pointee.ifa_next }
                guard let interface = ptr?.pointee else { continue }
                let flags = Int32(interface.ifa_flags)
                let isUp = (flags & IFF_UP) == IFF_UP
                let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK
                guard isUp, !isLoopback else { continue }
                guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }

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
                if candidate.hasPrefix("192.168.") || candidate.hasPrefix("10.") || candidate.hasPrefix("172.") {
                    address = candidate
                    break
                }
                if address == nil {
                    address = candidate
                }
            }
        }

        if let ifaddr {
            freeifaddrs(ifaddr)
        }
        return address ?? "127.0.0.1"
    }
}
