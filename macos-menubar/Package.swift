// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "SmsRelayMenuBar",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "SmsRelayMenuBar", targets: ["SmsRelayMenuBar"])
    ],
    targets: [
        .executableTarget(
            name: "SmsRelayMenuBar",
            path: "Sources/SmsRelayMenuBar",
            resources: [
                .process("Resources")
            ]
        )
    ]
)
