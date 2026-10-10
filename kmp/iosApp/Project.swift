import ProjectDescription

// Xcode 未继承 shell 的 JAVA_HOME 时回退到 Android Studio 自带 JBR（Gradle 8.14 不支持 JDK 25）。
let embedSharedFramework = """
set -e
if [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
cd "$SRCROOT/.."
./gradlew :app:shared:embedAndSignAppleFrameworkForXcode
"""

let project = Project(
    name: "iosApp",
    organizationName: "GOIM",
    targets: [
        .target(
            name: "GOIMKMP",
            destinations: .iOS,
            product: .app,
            bundleId: "com.goim.kmp",
            deploymentTargets: .iOS("16.0"),
            infoPlist: .extendingDefault(
                with: [
                    "CFBundleDisplayName": "GO-IM KMP",
                    "UILaunchScreen": [:],
                    "CADisableMinimumFrameDurationOnPhone": true,
                    "NSMicrophoneUsageDescription": "发送语音消息需要使用麦克风",
                    "NSCameraUsageDescription": "拍照发送图片需要使用相机",
                    "NSPhotoLibraryUsageDescription": "发送图片和视频需要访问相册",
                    "NSAppTransportSecurity": [
                        "NSAllowsLocalNetworking": true,
                    ],
                ]
            ),
            sources: ["Sources/**"],
            scripts: [
                .pre(
                    script: embedSharedFramework,
                    name: "Compile Kotlin Framework",
                    basedOnDependencyAnalysis: false
                ),
            ],
            settings: .settings(
                base: [
                    "SWIFT_VERSION": "5.0",
                    "ENABLE_USER_SCRIPT_SANDBOXING": "NO",
                    "FRAMEWORK_SEARCH_PATHS": "$(inherited) $(SRCROOT)/../app/shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)",
                    "OTHER_LDFLAGS": "$(inherited) -framework Shared",
                ]
            )
        ),
    ]
)
