// swift-tools-version: 6.0
import PackageDescription

#if TUIST
    import struct ProjectDescription.PackageSettings

    let packageSettings = PackageSettings(
        productTypes: [
            "Moya": .framework,
            "Alamofire": .framework,
            "GRDB": .framework,
            "Kingfisher": .framework,
            "SwiftProtobuf": .framework,
            "FLEX": .framework,
        ]
    )
#endif

let package = Package(
    name: "GOIMDependencies",
    dependencies: [
        .package(url: "https://github.com/Moya/Moya.git", from: "15.0.3"),
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.4.0"),
        .package(url: "https://github.com/onevcat/Kingfisher.git", from: "8.1.0"),
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/FLEXTool/FLEX.git", from: "5.22.10"),
    ]
)
