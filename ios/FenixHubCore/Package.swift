// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FenixHubCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(
            name: "FenixHubCore",
            targets: ["FenixHubCore"]
        ),
    ],
    dependencies: [
        .package(
            url: "https://github.com/iosdevlog/Argon2Swift.git",
            from: "1.0.0"
        ),
    ],
    targets: [
        .target(
            name: "FenixHubCore",
            dependencies: [
                .product(name: "Argon2Swift", package: "Argon2Swift"),
            ],
            exclude: ["App"]
        ),
        .testTarget(
            name: "FenixHubCoreTests",
            dependencies: ["FenixHubCore"]
        ),
    ]
)
