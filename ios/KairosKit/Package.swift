// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "KairosKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "KairosKit", targets: ["KairosKit"]),
    ],
    targets: [
        .target(name: "KairosKit"),
        .testTarget(
            name: "KairosKitTests",
            dependencies: ["KairosKit"]
        ),
    ]
)
