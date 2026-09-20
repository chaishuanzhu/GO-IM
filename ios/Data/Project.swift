import ProjectDescription

let project = Project(
    name: "Data",
    organizationName: "GOIM",
    targets: [
        .target(
            name: "Data",
            destinations: .iOS,
            product: .framework,
            bundleId: "com.goim.data",
            deploymentTargets: .iOS("16.0"),
            sources: ["Sources/**"],
            dependencies: [
                .project(target: "Domain", path: "../Domain"),
                .external(name: "Moya"),
                .external(name: "GRDB"),
                .external(name: "Kingfisher"),
                .external(name: "SwiftProtobuf"),
            ],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "minimal",
                ]
            )
        ),
        .target(
            name: "DataTests",
            destinations: .iOS,
            product: .unitTests,
            bundleId: "com.goim.data.tests",
            deploymentTargets: .iOS("16.0"),
            sources: ["Tests/**"],
            dependencies: [.target(name: "Data")],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "minimal",
                ]
            )
        ),
    ]
)
