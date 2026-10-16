// Jenkins job go-im-server (Pipeline script, not SCM): build, publish and deploy the GO-IM server side.
//   Images  ci-01: gateway / logic images of one commit, tagged sha-<7>, pushed to Docker Hub
//           (skipped when both tags already exist, unless REBUILD)
//   Deploy  ci-01: kubectl set image in namespace go-im-test (logic -> gateway, each waits for its rollout),
//           rolls back on failure, then checks https://go-im.test.chaisz.com/health
// Release builds (version tags, installers) stay in go-im. Infra, config Secret and manifests: scripts/17-goim-test.ps1.
//
// Parameters: BRANCH, DOCKERHUB_NAMESPACE (empty = dockerhub credential user), PLATFORMS, REBUILD, DEPLOY
// Credentials:
//   dockerhub                Username with password  Docker Hub user + Read & Write access token
//   k8s-deployer-kubeconfig  Secret file             ServiceAccount jenkins/deployer (edit in go-im-test)

def repoUrl = 'https://github.com/chaishuanzhu/GO-IM.git'
def k8sNamespace = 'go-im-test'
def siteUrl = 'https://go-im.test.chaisz.com'
def commit = ''
def tag = ''
def imageNs = ''

stage('Resolve') {
    node('docker') {
        retry(3) {
            commit = sh(script: "git ls-remote ${repoUrl} refs/heads/${params.BRANCH}", returnStdout: true).trim().tokenize()[0] ?: ''
        }
        if (!(commit ==~ /[0-9a-f]{40}/)) { error "分支 ${params.BRANCH} 不存在" }
        tag = "sha-${commit.substring(0, 7)}"
        currentBuild.description = "${params.BRANCH} @ ${commit.substring(0, 7)}"
    }
}

stage('Images') {
    node('docker') {
        try {
            withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                imageNs = params.DOCKERHUB_NAMESPACE?.trim() ?: env.DH_USER
                withEnv(["NS=${imageNs}", "TAG=${tag}", "COMMIT=${commit}", "PLATFORMS=${params.PLATFORMS}"]) {
                    sh 'echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin'
                    def missing = params.REBUILD ? 'gateway logic' : sh(returnStdout: true, script: '''
                        for n in gateway logic; do
                            docker buildx imagetools inspect "$NS/go-im-$n:$TAG" >/dev/null 2>&1 || printf '%s ' "$n"
                        done
                    ''').trim()
                    if (!missing) {
                        echo "${imageNs}/go-im-{gateway,logic}:${tag} 已存在，跳过构建"
                        return
                    }
                    deleteDir()
                    retry(3) {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: commit]],
                            userRemoteConfigs: [[url: repoUrl, refspec: "+refs/heads/${params.BRANCH}:refs/remotes/origin/${params.BRANCH}"]],
                            extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true, honorRefspec: true, timeout: 20]],
                        ])
                    }
                    withEnv(["MISSING=${missing}"]) {
                        sh '''
                            set -eu
                            # Compile on the build host's architecture (Go cross-compiles by TARGETARCH) instead of
                            # running go build under QEMU for arm64.
                            for f in Dockerfile.gateway Dockerfile.logic; do
                                sed -i -E 's#^FROM golang:([^ ]+) AS builder#FROM --platform=$BUILDPLATFORM golang:\\1 AS builder\\nARG TARGETOS TARGETARCH#' "$f"
                                sed -i -E 's#CGO_ENABLED=0 go build#CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build#' "$f"
                            done
                            for name in $MISSING; do
                                file=Dockerfile.$name
                                for i in 1 2 3; do
                                    docker buildx build --platform "$PLATFORMS" -f "$file" \
                                        --label org.opencontainers.image.source=https://github.com/chaishuanzhu/GO-IM \
                                        --label org.opencontainers.image.revision="$COMMIT" \
                                        -t "$NS/go-im-$name:$TAG" --push . && break
                                    [ "$i" = 3 ] && exit 1
                                    sleep 15
                                done
                            done
                        '''
                    }
                }
            }
        } finally {
            sh 'docker logout || true'
            deleteDir()
        }
    }
}

stage('Deploy') {
    if (!params.DEPLOY) {
        echo '跳过部署'
        return
    }
    node('docker') {
        withCredentials([file(credentialsId: 'k8s-deployer-kubeconfig', variable: 'KUBECONFIG')]) {
            withEnv(["NS=${imageNs}", "TAG=${tag}", "K8S_NS=${k8sNamespace}"]) {
                sh '''
                    set -eu
                    k() { kubectl -n "$K8S_NS" "$@"; }
                    done_list=""
                    for d in logic gateway; do
                        k set image "deployment/$d" "$d=$NS/go-im-$d:$TAG"
                        done_list="$done_list $d"
                        if ! k rollout status "deployment/$d" --timeout=5m; then
                            k get pods -o wide || true
                            k logs "deployment/$d" --all-containers --tail=50 || true
                            for u in $done_list; do k rollout undo "deployment/$u" || true; done
                            echo "deployment/$d 未就绪，已回滚：$done_list"
                            exit 1
                        fi
                    done
                    k get pods -o wide
                '''
            }
        }
        withEnv(["SITE=${siteUrl}"]) {
            sh '''
                set -u
                for i in $(seq 1 12); do
                    out=$(curl -fsS --max-time 10 "$SITE/health" 2>&1) && echo "$out" | grep -q '"status":"ok"' && { echo "$out"; exit 0; }
                    sleep 5
                done
                echo "健康检查失败：$out"
                exit 1
            '''
        }
        currentBuild.description += " → ${k8sNamespace}"
    }
}
