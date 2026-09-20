import ProjectDescription

let project = Project(
    name: "Domain",
    organizationName: "GOIM",
    targets: [
        .target(
            name: "Domain",
            destinations: .iOS,
            product: .framework,
            bundleId: "com.goim.domain",
            deploymentTargets: .iOS("16.0"),
            sources: ["Sources/**"],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "targeted",
                ]
            )
        ),
        .target(
            name: "DomainTests",
            destinations: .iOS,
            product: .unitTests,
            bundleId: "com.goim.domain.tests",
            deploymentTargets: .iOS("16.0"),
            sources: ["Tests/**"],
            dependencies: [.target(name: "Domain")],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "targeted",
                ]
            )
        ),
    ]
)
