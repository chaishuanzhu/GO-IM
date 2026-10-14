# Jenkins 任务脚本

[jenkins.chaisz.com](https://jenkins.chaisz.com) 上 GO-IM 相关任务的 Pipeline 脚本。任务类型均为「Pipeline script」（脚本存在任务配置里，非 SCM）：Jenkins 控制器到 GitHub 的网络不稳定，检出只在构建节点上做。

**这里的文件是任务配置的副本**：改完脚本后需粘贴到对应任务的配置页（Pipeline → Script）才会生效；在 Jenkins 上直接改了脚本，也请同步回这里。参数和触发器定义在任务配置中，见下表。

| 任务 | 脚本 | 触发 | 作用 |
|------|------|------|------|
| `go-im` | [go-im.groovy](go-im.groovy) | 手动；或由 `go-im-tags` 以 `TAG` 参数启动 | 全端客户端构建（Android / macOS / iOS / Windows / Linux）、服务端与 Web 镜像推送 Docker Hub、发布 GitHub Release `kmp-v<VERSION>`、部署 k8s 测试环境 |
| `go-im-tags` | [go-im-tags.groovy](go-im-tags.groovy) | 定时轮询 `H/3 * * * *` | 发现新的 `kmp-v<MAJOR.MINOR.PATCH>` tag 时启动 `go-im` |
| `go-im-server` | [go-im-server.groovy](go-im-server.groovy) | 手动 | 只构建服务端镜像（tag `sha-<7>`）并部署测试环境 |

## 发布新版本

```bash
git tag kmp-v1.2.0 && git push origin kmp-v1.2.0
```

`go-im-tags` 最多 3 分钟内发现新 tag，以 `TAG=kmp-v1.2.0` 启动 `go-im`：版本号与提交取自 tag，产物上传到同名 GitHub Release（发布脚本见 [kmp/ci/publish-github-release.sh](../../kmp/ci/publish-github-release.sh)）。落地页（[site/](../../site/)）运行时读取最新 Release，无需重新部署。

镜像推送后，`go-im` 用 [deploy/dockerhub/sync-readme.sh](../../deploy/dockerhub/sync-readme.sh) 把 `deploy/dockerhub/*.md` 同步为 Docker Hub 仓库的 Overview；同步失败只告警，不影响构建。

## 任务参数

**go-im**：`TAG`（设置后忽略 `BRANCH` / `VERSION`）、`BRANCH`、`VERSION`（MAJOR ≥ 1，DMG / MSI 的限制）、`BUILD_ANDROID`、`BUILD_DESKTOP`（macOS）、`BUILD_IOS`、`BUILD_WINDOWS`、`BUILD_LINUX`、`BUILD_IMAGES`、`DOCKERHUB_NAMESPACE`（留空 = Docker Hub 凭据的用户名）、`PUBLISH`、`PRERELEASE`、`DEPLOY_TEST`

**go-im-server**：`BRANCH`、`DOCKERHUB_NAMESPACE`、`PLATFORMS`、`REBUILD`、`DEPLOY`

## 节点

| 标签 | 机器 | 用途 |
|------|------|------|
| `node-mac` | Mac mini | Android APK、macOS DMG、iOS IPA，汇总产物并发布 Release |
| `windows` | win-host | Windows MSI（jpackage 只能在目标系统上打包） |
| `docker` | ci-01 | Linux DEB（容器内构建）、Docker 镜像、k8s 部署、tag 轮询 |

## 凭据

| ID | 类型 | 用途 |
|------|------|------|
| `goim-android-keystore` | Secret file | Android release keystore |
| `goim-android-signing` | Username with password | 用户名 = key alias，密码 = keystore / key 密码 |
| `github-token` | Secret text | 创建 / 编辑 GitHub Release |
| `dockerhub` | Username with password | Docker Hub 用户名 + Read & Write access token |
| `k8s-deployer-kubeconfig` | Secret file | ServiceAccount `jenkins/deployer`（在 `go-im-test` 有 edit 权限） |
