// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CameraTriggerKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CameraTriggerKit", targets: ["CameraTriggerKit"]),
    ],
    targets: [
        .target(name: "CameraTriggerKit"),
        .testTarget(
            name: "CameraTriggerKitTests",
            dependencies: ["CameraTriggerKit"]
        ),
    ]
)
