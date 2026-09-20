import ProjectDescription

let project = Project(
    name: "App",
    organizationName: "GOIM",
    targets: [
        .target(
            name: "GOIM",
            destinations: .iOS,
            product: .app,
            bundleId: "com.goim.app",
            deploymentTargets: .iOS("16.0"),
            infoPlist: .extendingDefault(
                with: [
                    "UILaunchScreen": [:],
                    "UIApplicationSceneManifest": [
                        "UIApplicationSupportsMultipleScenes": false,
                        "UISceneConfigurations": [
                            "UIWindowSceneSessionRoleApplication": [
                                [
                                    "UISceneConfigurationName": "Default Configuration",
                                    "UISceneDelegateClassName": "$(PRODUCT_MODULE_NAME).SceneDelegate",
                                ],
                            ],
                        ],
                    ],
                    "NSAppTransportSecurity": [
                        "NSAllowsLocalNetworking": true,
                    ],
                    "NSPhotoLibraryUsageDescription": "选择图片或视频发送到聊天",
                    "NSCameraUsageDescription": "拍摄照片发送到聊天",
                    "NSMicrophoneUsageDescription": "录制语音消息发送到聊天",
                    "API_BASE_URL": "$(API_BASE_URL)",
                ]
            ),
            sources: ["Sources/**"],
            dependencies: [
                .project(target: "Presentation", path: "../Presentation"),
                .project(target: "Data", path: "../Data"),
                .project(target: "Domain", path: "../Domain"),
                .external(name: "Moya"),
            ],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "6.0",
                    "SWIFT_STRICT_CONCURRENCY": "minimal",
                    "API_BASE_URL": "https://im.chaisz.com",
                ],
                configurations: [
                    .debug(name: "Debug", settings: ["API_BASE_URL": "https://im.chaisz.com"]),
                    .release(name: "Release", settings: ["API_BASE_URL": "https://im.chaisz.com"]),
                ]
            )
        ),
    ]
)
