// Jenkins 任务 go-im-ci 的任务脚本（Pipeline script，非 SCM）：持续集成。
//   Go   ci-01      gofmt / go vet / go build → 单元测试（含 MySQL）→ Kafka 端到端 → gateway / logic 集成测试
//                   → 构建 gateway / logic 镜像（不推送）。ci-01 内存小，MySQL、Kafka 依次启动，不同时运行；
//                   依赖容器与 Go 容器共用网络命名空间，测试按 localhost:3306 / localhost:9092 连接。
//   KMP  node-mac   ./gradlew check（Android / Desktop / iOS 模拟器单测、Android Lint）+ 链接 iOS Shared.framework
// 触发：定时轮询（H/5），BRANCH 有新提交才构建；测过的提交记在 ci-01 的 /data/jenkins-agent/state/goim-ci-<分支>.txt。
//       Jenkins 只在内网，GitHub webhook 进不来，PR 不会自动测；手动启动并设 BRANCH 可测任意分支，FORCE 忽略「已测过」。
//
// 任务参数：BRANCH（默认 main）、FORCE

def repoUrl = 'https://github.com/chaishuanzhu/GO-IM.git'
def branch = params.BRANCH?.trim() ?: 'main'
def commit = ''

def checkoutCommit(String repoUrl, String branch, String commit) {
    deleteDir()
    retry(3) {
        checkout(changelog: false, poll: false, scm: [
            $class: 'GitSCM',
            branches: [[name: commit]],
            userRemoteConfigs: [[url: repoUrl, refspec: "+refs/heads/${branch}:refs/remotes/origin/${branch}"]],
            extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true, honorRefspec: true, timeout: 20]],
        ])
    }
}

stage('Resolve') {
    node('docker') {
        retry(3) {
            commit = sh(script: "git ls-remote ${repoUrl} refs/heads/${branch}", returnStdout: true).trim().tokenize()[0] ?: ''
        }
        if (!(commit ==~ /[0-9a-f]{40}/)) { error "分支 ${branch} 不存在" }
        def stateFile = "goim-ci-${branch.replaceAll(/[^A-Za-z0-9._-]/, '_')}.txt"
        dir('/data/jenkins-agent/state') {
            def last = fileExists(stateFile) ? readFile(stateFile).trim() : ''
            if (last == commit && !params.FORCE) {
                currentBuild.description = "${branch} @ ${commit.substring(0, 7)} 已测过"
                currentBuild.result = 'NOT_BUILT'
                commit = ''
                return
            }
            writeFile file: stateFile, text: commit + '\n'
        }
        currentBuild.description = "${branch} @ ${commit.substring(0, 7)}"
    }
}

if (commit) {
    stage('Test') {
        parallel(
            'Go': {
                node('docker') {
                    try {
                        checkoutCommit(repoUrl, branch, commit)
                        withEnv(["ID=goim-ci-${env.BUILD_NUMBER}"]) {
                            sh '''
                                set -eu
                                trap 'docker rm -f "$ID-mysql" "$ID-kafka" >/dev/null 2>&1 || true' EXIT
                                gorun() {
                                    net="$1"; shift
                                    docker run --rm ${net:+--network "container:$net"} -v "$PWD:/src" -w /src \
                                        -v goim-ci-gomod:/go/pkg/mod -v goim-ci-gocache:/root/.cache/go-build \
                                        -e GOPROXY=https://goproxy.cn,direct -e GOFLAGS=-buildvcs=false -e CGO_ENABLED=0 \
                                        golang:1.26.5-alpine sh -euc "$*"
                                }
                                wait_for() {
                                    for i in $(seq 1 60); do eval "$1" >/dev/null 2>&1 && return 0; sleep 3; done
                                    echo "$2 未就绪"; return 1
                                }

                                echo '== gofmt / vet / build'
                                gorun '' 'u=$(gofmt -l .); [ -z "$u" ] || { echo "未通过 gofmt：$u"; exit 1; }
                                    go vet ./internal/... ./cmd/... && go build ./cmd/gateway/ ./cmd/logic/'

                                echo '== 单元测试（MySQL 8.4）'
                                docker run -d --name "$ID-mysql" --memory 512m \
                                    -e MYSQL_ROOT_PASSWORD=im-dev-root -e MYSQL_DATABASE=im -e MYSQL_USER=im -e MYSQL_PASSWORD=im-dev \
                                    mysql:8.4 --mysql-native-password=ON --performance-schema=OFF --innodb-buffer-pool-size=32M >/dev/null
                                wait_for 'docker exec "$ID-mysql" mysql -uim -pim-dev -h127.0.0.1 -e "select 1" im' MySQL
                                gorun "$ID-mysql" 'go test -p 2 -count=1 ./internal/...'
                                docker rm -f "$ID-mysql" >/dev/null

                                echo '== Kafka 端到端'
                                docker run -d --name "$ID-kafka" --memory 640m \
                                    -e KAFKA_HEAP_OPTS='-Xms256m -Xmx384m' -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
                                    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
                                    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
                                    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
                                    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
                                    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
                                    apache/kafka:3.9.0 >/dev/null
                                wait_for 'docker exec "$ID-kafka" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list' Kafka
                                gorun "$ID-kafka" 'go test -count=1 -v ./internal/mq/'
                                docker rm -f "$ID-kafka" >/dev/null

                                echo '== 集成测试（WebSocket + gnet TCP）'
                                gorun '' 'go test -count=1 -v ./cmd/gateway/'

                                echo '== 镜像构建（不推送）'
                                for n in gateway logic; do
                                    docker build -q -f "Dockerfile.$n" -t "$ID-$n" . >/dev/null
                                    docker rmi "$ID-$n" >/dev/null
                                    echo "$n ok"
                                done
                            '''
                        }
                    } finally {
                        deleteDir()
                    }
                }
            },
            'KMP': {
                node('node-mac') {
                    try {
                        checkoutCommit(repoUrl, branch, commit)
                        withEnv([
                            'LANG=en_US.UTF-8',
                            'JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home',
                            'ANDROID_HOME=/Users/chaisz/Library/Android/sdk',
                        ]) {
                            dir('kmp') {
                                sh './gradlew check :app:shared:linkDebugFrameworkIosSimulatorArm64 --console=plain --no-daemon --no-configuration-cache'
                            }
                        }
                    } finally {
                        junit allowEmptyResults: true, testResults: 'kmp/**/build/test-results/**/*.xml'
                        deleteDir()
                    }
                }
            },
        )
    }
}
