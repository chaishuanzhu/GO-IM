// Jenkins 任务 go-im 的任务脚本（Pipeline script，非 SCM）：全端构建并发布。
//   node-mac   Android APK、macOS DMG（Developer ID 签名 + 公证）、iOS 未签名 IPA
//   win-host   Windows MSI（jpackage 只能在目标系统上打包）
//   ci-01      Linux DEB（容器内构建）；服务端 gateway / logic 多架构镜像推送 Docker Hub
//   最后在 node-mac 汇总产物发布到 GitHub Release（tag v<VERSION>，已存在则覆盖同名产物）
//   镜像推送后把 <VERSION> 镜像部署到 k8s 测试环境 go-im-test（https://test-go-im.chaisz.com）
//   TAG 构建在测试环境通过后再部署生产（docker compose，仅 gateway；地址见凭据 goim-prod-target）
// 各节点各自检出同一个提交；Jenkins 控制器到 GitHub 的网络不稳定，控制器上不做检出。
// 自动触发：任务 go-im-tags 轮询 GitHub，发现新的 v<版本> tag 就以 TAG 参数启动本任务。
//
// 任务参数：TAG（v<版本>，设置后版本号和提交取自 tag，忽略 BRANCH / VERSION）、BRANCH、VERSION、
//           BUILD_ANDROID、BUILD_DESKTOP（macOS）、BUILD_IOS、BUILD_WINDOWS、BUILD_LINUX、
//           BUILD_IMAGES、DOCKERHUB_NAMESPACE（留空 = Docker Hub 凭据的用户名）、PUBLISH、PRERELEASE、DEPLOY_TEST、
//           DEPLOY_PROD（只对 TAG 构建生效）
// Jenkins 凭据：
//   goim-android-keystore   Secret file             Android release keystore
//   goim-android-signing    Username with password  用户名 = key alias，密码 = keystore / key 密码
//   goim-macos-devid-p12    Secret file             Developer ID Application 证书 + 私钥（.p12）
//   goim-macos-devid-password Secret text           上面 .p12 的密码
//   goim-asc-api-key        Secret file             App Store Connect API Key（.p8），公证用
//   goim-asc-api-ids        Username with password  用户名 = Key ID，密码 = Issuer ID
//   github-token            Secret text             可写 Release 的 GitHub token
//   dockerhub               Username with password  Docker Hub 用户名 + Read & Write access token
//   k8s-deployer-kubeconfig Secret file             ServiceAccount jenkins/deployer（在 go-im-test 有 edit 权限）
//   goim-prod-ssh           SSH Username with private key  生产服务器 root，authorized_keys 限定只能执行
//                                                   /root/docker/go-im/deploy.sh（即 ci/jenkins/prod-deploy.sh）
//   goim-prod-target        Secret file             shell 变量：PROD_HOST、PROD_HOST_KEY（known_hosts 中的
//                                                   "类型 公钥"）、PROD_URL（生产站点根地址）

def repoUrl = 'https://github.com/chaishuanzhu/GO-IM.git'
def gitTag = params.TAG?.trim() ?: ''
def version = params.VERSION.trim()
def sourceRef = "refs/heads/${params.BRANCH}"
if (gitTag) {
    def m = gitTag =~ /^v([1-9][0-9]*\.[0-9]+\.[0-9]+)$/
    if (!m.matches()) { error "TAG 须为 v<MAJOR.MINOR.PATCH> 且 MAJOR ≥ 1：${gitTag}" }
    version = m.group(1)
    m = null
    sourceRef = "refs/tags/${gitTag}"
}
if (!(version ==~ /[1-9][0-9]*\.[0-9]+\.[0-9]+/)) {
    error "VERSION 须为 MAJOR.MINOR.PATCH 且 MAJOR ≥ 1（DMG / MSI 的限制）：${version}"
}
def tag = "v${version}"
def gradleArgs = "--console=plain --no-daemon --no-configuration-cache -Pgoim.versionName=${version} -Pgoim.versionCode=${env.BUILD_NUMBER}"
def commit = ''
def images = []
def imageNs = ''
// 生产 docker-compose.yml 写死了这个命名空间的 gateway 镜像
def prodImageNs = '870027381'

def checkoutCommit(String repoUrl, String commit) {
    def ref = params.TAG?.trim() ? "refs/tags/${params.TAG.trim()}" : "refs/heads/${params.BRANCH}"
    def local = params.TAG?.trim() ? ref : "refs/remotes/origin/${params.BRANCH}"
    deleteDir()
    retry(3) {
        checkout(changelog: false, poll: false, scm: [
            $class: 'GitSCM',
            branches: [[name: commit]],
            userRemoteConfigs: [[url: repoUrl, refspec: "+${ref}:${local}"]],
            extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true, honorRefspec: true, timeout: 20]],
        ])
    }
}

stage('Resolve') {
    node('docker') {
        retry(3) {
            // 附注 tag 的 ls-remote 结果里，^{} 那一行才是它指向的提交
            def refs = sh(script: "git ls-remote ${repoUrl} '${sourceRef}' '${sourceRef}^{}'", returnStdout: true).trim()
            def rows = refs ? refs.readLines().collect { it.tokenize('\t') } : []
            def row = rows.find { it[1].endsWith('^{}') } ?: (rows ? rows[0] : null)
            commit = row ? row[0] : ''
        }
        if (!(commit ==~ /[0-9a-f]{40}/)) { error "${sourceRef} 不存在" }
        currentBuild.description = "${version} @ ${commit.substring(0, 7)}" + (gitTag ? " (${gitTag})" : '')
    }
}

def branches = [:]

if (params.BUILD_ANDROID || params.BUILD_DESKTOP || params.BUILD_IOS) {
    branches['Apple + Android'] = {
        node('node-mac') {
            try {
                checkoutCommit(repoUrl, commit)
                withEnv([
                    'LANG=en_US.UTF-8',
                    // Gradle 8.14 不支持 JDK 25，编译用 Android Studio 自带 JBR；DMG 打包需要带 jpackage 的完整 JDK
                    'JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home',
                    'PACKAGE_JAVA_HOME=/Users/chaisz/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home',
                    'ANDROID_HOME=/Users/chaisz/Library/Android/sdk',
                    "VERSION=${version}",
                    "GRADLE_ARGS=${gradleArgs}",
                ]) {
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
                                    cp app/android/build/outputs/apk/release/android-release.apk "dist/GO-IM-$VERSION-android.apk"
                                '''
                            }
                        }
                        stage('macOS') {
                            if (!params.BUILD_DESKTOP) { echo '跳过'; return }
                            // 签名、DMG、公证见 kmp/ci/macos-sign-notarize.sh（逐个文件重试时间戳）；
                            // agent 进程读不到登录钥匙串里 asc 保存的 API Key，公证凭据从 Jenkins 传入
                            withCredentials([
                                file(credentialsId: 'goim-macos-devid-p12', variable: 'DEVID_P12'),
                                string(credentialsId: 'goim-macos-devid-password', variable: 'DEVID_P12_PASSWORD'),
                                file(credentialsId: 'goim-asc-api-key', variable: 'ASC_PRIVATE_KEY_PATH'),
                                usernamePassword(credentialsId: 'goim-asc-api-ids', usernameVariable: 'ASC_KEY_ID', passwordVariable: 'ASC_ISSUER_ID'),
                            ]) {
                                sh '''
                                    export ASC_BYPASS_KEYCHAIN=1
                                    ./gradlew :app:desktop:createDistributable $GRADLE_ARGS -Pgoim.packageJavaHome="$PACKAGE_JAVA_HOME"
                                    bash ci/macos-sign-notarize.sh app/desktop/build/compose/binaries/main/app/GO-IM.app "dist/GO-IM-$VERSION-macos-arm64.dmg"
                                '''
                            }
                        }
                        stage('iOS') {
                            if (!params.BUILD_IOS) { echo '跳过'; return }
                            sh '''
                                (cd iosApp && tuist generate --no-open)
                                xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme GOIMKMP -configuration Release \
                                    -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/ios \
                                    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build
                                APP=build/ios/Build/Products/Release-iphoneos/GOIMKMP.app
                                /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Info.plist"
                                /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$APP/Info.plist"
                                rm -rf build/ipa && mkdir -p build/ipa/Payload
                                cp -R "$APP" build/ipa/Payload/
                                (cd build/ipa && zip -qry "../../dist/GO-IM-$VERSION-ios-unsigned.ipa" Payload)
                            '''
                        }
                        stash name: 'dist-apple', includes: 'dist/*', allowEmpty: true
                    }
                }
            } finally {
                deleteDir()
            }
        }
    }
}

if (params.BUILD_WINDOWS) {
    branches['Windows'] = {
        node('windows') {
            try {
                checkoutCommit(repoUrl, commit)
                dir('kmp') {
                    // JAVA_HOME（Temurin 21，含 jpackage）、ANDROID_HOME、GRADLE_USER_HOME 由节点的 run-agent.cmd 提供
                    // services.gradle.org 跳转到 GitHub Release，这些节点经路由器代理访问 GitHub 时常只有几十 KB/s
                    bat """
                        powershell -NoProfile -Command "(Get-Content gradle\\wrapper\\gradle-wrapper.properties) -replace 'services.gradle.org/distributions','mirrors.cloud.tencent.com/gradle' | Set-Content -Encoding ascii gradle\\wrapper\\gradle-wrapper.properties"
                        if exist dist rmdir /s /q dist
                        mkdir dist
                        call gradlew.bat :app:desktop:packageMsi ${gradleArgs}
                        if errorlevel 1 exit /b 1
                        copy /y app\\desktop\\build\\compose\\binaries\\main\\msi\\GO-IM-${version}.msi dist\\GO-IM-${version}-windows-x64.msi
                    """
                    stash name: 'dist-windows', includes: 'dist/*'
                }
            } finally {
                deleteDir()
            }
        }
    }
}

if (params.BUILD_LINUX) {
    branches['Linux'] = {
        node('docker') {
            try {
                checkoutCommit(repoUrl, commit)
                // 构建环境：Temurin 21（jpackage）+ fakeroot/binutils（打 deb）+ Android SDK（KMP 配置 Android 模块需要）
                writeFile file: '.ci/linux-builder.Dockerfile', text: '''FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends fakeroot binutils unzip && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN curl -fsSL --retry 5 -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \\
 && mkdir -p $ANDROID_HOME/cmdline-tools && unzip -q /tmp/tools.zip -d /tmp/tools \\
 && mv /tmp/tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm -rf /tmp/tools /tmp/tools.zip \\
 && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \\
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;35.0.0" > /dev/null \\
 && chmod -R a+rX $ANDROID_HOME
'''
                withEnv(["VERSION=${version}", "GRADLE_ARGS=${gradleArgs}"]) {
                    sh '''
                        retry() { for i in 1 2 3; do "$@" && return 0; sleep 10; done; return 1; }
                        retry docker build -q -t goim-linux-builder:jdk21 -f .ci/linux-builder.Dockerfile .ci
                        sed -i 's#services.gradle.org/distributions#mirrors.cloud.tencent.com/gradle#' kmp/gradle/wrapper/gradle-wrapper.properties
                        mkdir -p "$HOME/.cache/goim-gradle" kmp/dist
                        docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GRADLE_USER_HOME=/cache/gradle \
                            -v "$HOME/.cache/goim-gradle:/cache/gradle" -v "$PWD:/src" -w /src/kmp \
                            goim-linux-builder:jdk21 ./gradlew :app:desktop:packageDeb $GRADLE_ARGS
                        cp kmp/app/desktop/build/compose/binaries/main/deb/*.deb "kmp/dist/GO-IM-$VERSION-linux-amd64.deb"
                    '''
                }
                dir('kmp') { stash name: 'dist-linux', includes: 'dist/*' }
            } finally {
                deleteDir()
            }
        }
    }
}

if (params.BUILD_IMAGES) {
    branches['Docker images'] = {
        node('docker') {
            try {
                checkoutCommit(repoUrl, commit)
                withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    def ns = params.DOCKERHUB_NAMESPACE?.trim() ?: env.DH_USER
                    withEnv(["NS=${ns}", "VERSION=${version}", "COMMIT=${commit}"]) {
                        sh '''
                            set -eu
                            echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                            # 编译阶段固定在构建机架构上（Go 按 TARGETARCH 交叉编译），避免在 QEMU 模拟的 arm64 里跑 go build。
                            for f in Dockerfile.gateway Dockerfile.logic; do
                                sed -i -E 's#^FROM golang:([^ ]+) AS builder#FROM --platform=$BUILDPLATFORM golang:\\1 AS builder\\nARG TARGETOS TARGETARCH#' "$f"
                                sed -i -E 's#CGO_ENABLED=0 go build#CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build#' "$f"
                            done
                            for svc in gateway:Dockerfile.gateway logic:Dockerfile.logic; do
                                name=${svc%%:*}; file=${svc#*:}
                                for i in 1 2 3; do
                                    docker buildx build --platform linux/amd64,linux/arm64 -f "$file" \
                                        --label org.opencontainers.image.source=https://github.com/chaishuanzhu/GO-IM \
                                        --label org.opencontainers.image.revision="$COMMIT" \
                                        --label org.opencontainers.image.version="$VERSION" \
                                        -t "$NS/go-im-$name:$VERSION" -t "$NS/go-im-$name:latest" --push . && break
                                    [ "$i" = 3 ] && exit 1
                                    sleep 15
                                done
                            done
                            if [ -f deploy/dockerhub/sync-readme.sh ]; then
                                DH_NAMESPACE="$NS" bash deploy/dockerhub/sync-readme.sh || echo "WARN: Docker Hub overview 同步失败"
                            fi
                        '''
                    }
                    images = ['gateway', 'logic'].collect { "${ns}/go-im-${it}:${version}" }
                    imageNs = ns
                }
            } finally {
                sh 'docker logout || true'
                deleteDir()
            }
        }
    }
}

stage('Build') {
    parallel branches
}

stage('Publish') {
    node('node-mac') {
        try {
            deleteDir()
            dir('kmp') {
                ['dist-apple', 'dist-windows', 'dist-linux'].each { name ->
                    try { unstash name } catch (e) { echo "无产物：${name}" }
                }
                archiveArtifacts artifacts: 'dist/*', allowEmptyArchive: true, fingerprint: true
            }
            if (!params.PUBLISH) { echo '跳过发布'; return }
            def files = sh(script: 'cd kmp/dist 2>/dev/null && ls GO-IM-* 2>/dev/null || true', returnStdout: true).trim().readLines()
            if (!files) { echo '没有安装包可发布'; return }

            def platforms = [
                'android.apk': 'Android 8.0+',
                'macos-arm64.dmg': 'macOS（Apple Silicon，已签名并公证）',
                'ios-unsigned.ipa': 'iOS 16+，未签名，需自行重签名或侧载',
                'windows-x64.msi': 'Windows 10/11 x64（未签名：SmartScreen 提示时选「仍要运行」）',
                'linux-amd64.deb': 'Debian / Ubuntu x64（`sudo apt install ./GO-IM-*.deb`）',
            ]
            def rows = platforms.findAll { suffix, desc -> files.contains("GO-IM-${version}-${suffix}".toString()) }
                .collect { suffix, desc -> "| `GO-IM-${version}-${suffix}` | ${desc} |" }
            def notes = "GO-IM ${version}（commit ${commit.substring(0, 7)}，Jenkins #${env.BUILD_NUMBER}）\n\n" +
                "| 文件 | 平台 |\n|------|------|\n" + rows.join('\n') + '\n'
            if (images) {
                notes += "\n服务端镜像（linux/amd64、linux/arm64）：\n\n" + images.collect { "- `docker pull ${it}`" }.join('\n') + '\n'
            }
            writeFile file: 'notes.md', text: notes

            dir('ci-src') { checkoutCommit(repoUrl, commit) }
            withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                withEnv(["TAG=${tag}", "VERSION=${version}", "GIT_COMMIT=${commit}", "DIST=${pwd()}/kmp/dist", "PRERELEASE=${params.PRERELEASE}"]) {
                    retry(3) {
                        sh '''
                            bash ci-src/kmp/ci/publish-github-release.sh
                            for i in 1 2 3 4 5; do
                                gh release edit "$TAG" -R chaishuanzhu/GO-IM --notes-file notes.md && break
                                sleep 15
                            done
                        '''
                    }
                }
            }
        } finally {
            deleteDir()
        }
    }
}

stage('Deploy test') {
    if (!params.DEPLOY_TEST || !imageNs) {
        echo imageNs ? '跳过部署' : '本次未推送镜像，跳过部署'
        return
    }
    node('docker') {
        withCredentials([file(credentialsId: 'k8s-deployer-kubeconfig', variable: 'KUBECONFIG')]) {
            withEnv(["NS=${imageNs}", "TAG=${version}", "K8S_NS=go-im-test"]) {
                sh '''
                    set -eu
                    k() { kubectl -n "$K8S_NS" "$@"; }
                    changed=""
                    for d in logic gateway; do
                        k set image "deployment/$d" "$d=$NS/go-im-$d:$TAG"
                        changed="$changed $d"
                        if ! k rollout status "deployment/$d" --timeout=5m; then
                            k get pods -o wide || true
                            k logs "deployment/$d" --all-containers --tail=50 || true
                            for u in $changed; do k rollout undo "deployment/$u" || true; done
                            echo "deployment/$d 未就绪，已回滚：$changed"
                            exit 1
                        fi
                    done
                    k get pods -o wide
                '''
            }
        }
        sh '''
            set -u
            for i in $(seq 1 12); do
                out=$(curl -fsS --max-time 10 https://test-go-im.chaisz.com/health 2>&1) && echo "$out" | grep -q '"status":"ok"' && { echo "$out"; exit 0; }
                sleep 5
            done
            echo "健康检查失败：$out"
            exit 1
        '''
        currentBuild.description += ' → go-im-test'
    }
}

stage('Deploy prod') {
    if (!gitTag || !params.DEPLOY_PROD || imageNs != prodImageNs) {
        echo !gitTag ? '非 TAG 构建，不部署生产' :
            !params.DEPLOY_PROD ? '跳过生产部署' :
            imageNs ? "镜像推送到 ${imageNs} 而非 ${prodImageNs}，跳过生产部署" : '本次未推送镜像，跳过生产部署'
        return
    }
    node('docker') {
        // 生产地址只在凭据里；set +x 并丢弃 ssh / curl 的错误输出，避免地址出现在构建日志中
        withCredentials([
            sshUserPrivateKey(credentialsId: 'goim-prod-ssh', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER'),
            file(credentialsId: 'goim-prod-target', variable: 'PROD_TARGET'),
        ]) {
            withEnv(["TAG=${version}"]) {
                sh '''
                    set +x -eu
                    . "$PROD_TARGET"
                    kh="$(mktemp)"
                    trap 'rm -f "$kh"' EXIT
                    echo "$PROD_HOST $PROD_HOST_KEY" > "$kh"
                    rc=0
                    ssh -i "$SSH_KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=20 -o LogLevel=QUIET \
                        -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$kh" "$SSH_USER@$PROD_HOST" "$TAG" || rc=$?
                    [ "$rc" -eq 0 ] || { echo "生产部署失败（ssh / deploy.sh 退出码 $rc）"; exit 1; }

                    for i in $(seq 1 12); do
                        out=$(curl -fsS --max-time 10 "$PROD_URL/health" 2>/dev/null) && echo "$out" | grep -q '"status":"ok"' && { echo "$out"; exit 0; }
                        sleep 5
                    done
                    echo "生产健康检查失败"
                    exit 1
                '''
            }
        }
        currentBuild.description += ' → prod'
    }
}
