// KMP 客户端发布流程：构建 Android APK、macOS DMG、iOS 未签名 IPA，并发布到 GitHub Release。
//
// 由 Jenkins 任务 GO-IM-KMP 的任务脚本（ci/jenkins-job.groovy）在 node-mac 上检出仓库后 load 执行；
// 不用「Pipeline script from SCM」是因为 Jenkins 控制器到 GitHub 的网络不稳定，检出只在 Mac 节点上做。
//
// 任务参数：BRANCH、VERSION、BUILD_ANDROID、BUILD_DESKTOP、BUILD_IOS、PUBLISH、PRERELEASE
// Jenkins 凭据：
//   goim-android-keystore   Secret file             Android release keystore
//   goim-android-signing    Username with password  用户名 = key alias，密码 = keystore / key 密码
//   github-token            Secret text             可写 Release 的 GitHub token

def version = params.VERSION.trim()
if (!(version ==~ /[1-9][0-9]*\.[0-9]+\.[0-9]+/)) {
    error "VERSION 须为 MAJOR.MINOR.PATCH 且 MAJOR ≥ 1（DMG 的限制）：${version}"
}

withEnv([
    'LANG=en_US.UTF-8',
    // Gradle 8.14 不支持 JDK 25，编译用 Android Studio 自带 JBR；DMG 打包需要带 jpackage 的完整 JDK
    'JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home',
    'PACKAGE_JAVA_HOME=/Users/chaisz/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home',
    'ANDROID_HOME=/Users/chaisz/Library/Android/sdk',
    "VERSION=${version}",
    "TAG=kmp-v${version}",
    "DIST=${pwd()}/kmp/dist",
    "GRADLE_ARGS=--console=plain --no-configuration-cache -Pgoim.versionName=${version} -Pgoim.versionCode=${env.BUILD_NUMBER}",
]) {
    timeout(time: 90, unit: 'MINUTES') {
        dir('kmp') {
            sh 'rm -rf dist && mkdir -p dist'

            stage('Android') {
                if (!params.BUILD_ANDROID) { echo '跳过'; return }
                withCredentials([
                    file(credentialsId: 'goim-android-keystore', variable: 'GOIM_KEYSTORE_FILE'),
                    usernamePassword(credentialsId: 'goim-android-signing', usernameVariable: 'GOIM_KEY_ALIAS', passwordVariable: 'GOIM_KEYSTORE_PASSWORD'),
                ]) {
                    sh '''
                        export GOIM_KEY_PASSWORD="$GOIM_KEYSTORE_PASSWORD"
                        ./gradlew :app:android:assembleRelease $GRADLE_ARGS
                        cp app/android/build/outputs/apk/release/android-release.apk "$DIST/GO-IM-$VERSION-android.apk"
                    '''
                }
            }

            stage('Desktop') {
                if (!params.BUILD_DESKTOP) { echo '跳过'; return }
                sh '''
                    ./gradlew :app:desktop:packageDmg $GRADLE_ARGS -Pgoim.packageJavaHome="$PACKAGE_JAVA_HOME"
                    cp app/desktop/build/compose/binaries/main/dmg/GO-IM-$VERSION.dmg "$DIST/GO-IM-$VERSION-macos-arm64.dmg"
                '''
            }

            stage('iOS') {
                if (!params.BUILD_IOS) { echo '跳过'; return }
                sh '''
                    # Derived/（Info.plist 等）由 Tuist 生成且不入库
                    (cd iosApp && tuist generate --no-open)
                    xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme GOIMKMP -configuration Release \
                        -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/ios \
                        CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build

                    APP=build/ios/Build/Products/Release-iphoneos/GOIMKMP.app
                    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Info.plist"
                    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$APP/Info.plist"

                    rm -rf build/ipa && mkdir -p build/ipa/Payload
                    cp -R "$APP" build/ipa/Payload/
                    (cd build/ipa && zip -qry "$DIST/GO-IM-$VERSION-ios-unsigned.ipa" Payload)
                '''
            }

            // 先归档到 Jenkins，发布失败时也能从构建页下载
            archiveArtifacts artifacts: 'dist/*', fingerprint: true

            stage('Publish') {
                if (!params.PUBLISH) { echo '跳过'; return }
                withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                    sh 'ci/publish-github-release.sh'
                }
            }
        }
    }
}
