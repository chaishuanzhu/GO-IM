import ProjectDescription

let project = Project(
    name: "Presentation",
    organizationName: "GOIM",
    targets: [
        .target(
            name: "Presentation",
            destinations: .iOS,
            product: .framework,
            bundleId: "com.goim.presentation",
            deploymentTargets: .iOS("16.0"),
            sources: ["Sources/**"],
            dependencies: [
                .project(target: "Domain", path: "../Domain"),
                .external(name: "Kingfisher"),
                .sdk(name: "QuickLook", type: .framework),
            ],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "targeted",
                ]
            )
        ),
    ]
)
