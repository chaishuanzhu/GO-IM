# Jenkins 任务脚本

[jenkins.chaisz.com](https://jenkins.chaisz.com) 上 GO-IM 相关任务的 Pipeline 脚本。任务类型均为「Pipeline script」（脚本存在任务配置里，非 SCM）：Jenkins 控制器到 GitHub 的网络不稳定，检出只在构建节点上做。

**这里的文件是任务配置的副本**：改完脚本后需粘贴到对应任务的配置页（Pipeline → Script）才会生效；在 Jenkins 上直接改了脚本，也请同步回这里。参数和触发器定义在任务配置中，见下表。

| 任务 | 脚本 | 触发 | 作用 |
|------|------|------|------|
| `go-im` | [go-im.groovy](go-im.groovy) | 手动；或由 `go-im-tags` 以 `TAG` 参数启动 | 全端客户端构建（Android / macOS / iOS / Windows / Linux）、服务端与 Web 镜像推送 Docker Hub、发布 GitHub Release `v<VERSION>`、部署 k8s 测试环境 |
| `go-im-tags` | [go-im-tags.groovy](go-im-tags.groovy) | 定时轮询 `H/3 * * * *` | 发现新的 `v<MAJOR.MINOR.PATCH>` tag 时启动 `go-im` |
| `go-im-server` | [go-im-server.groovy](go-im-server.groovy) | 手动 | 只构建服务端镜像（tag `sha-<7>`）并部署测试环境 |

## 发布新版本

`go-im` 的所有产物都取自 **tag 指向的提交**：镜像、安装包，以及同步到 Docker Hub Overview 的 `deploy/dockerhub/*.md` + `deploy/README.md`。tag 之后才合入的文档不会出现在这次发布里，只能等下一次发布。因此顺序固定为：**改文档 → 合入 main → 在 main 上打 tag → 自动构建**。

1. **改文档、改版本号**（在功能分支上）。把下列文件中的上一个版本号改成新版本（`rg -n '\b<上一版本>\b' deploy` 可列出）：
   - `deploy/dockerhub/{gateway,logic,web}.md`：「`X.Y.Z` 等 … GitHub Release `vX.Y.Z`」
   - `deploy/k8s/kustomization.yaml`：三个镜像的 `newTag`
   - `deploy/README.md`、`deploy/docker/docker-compose*.yml`：`GOIM_TAG` 示例
   - 落地页 / Docker Hub 文案如有新功能，一并更新 `site/index.html`、`deploy/dockerhub/*.md`
2. **合入 main 并推送**。main 上 `site/**` 有变更时 Pages 工作流会自动重新部署落地页。
3. **在 GitHub 上的 main 最新提交上打 tag**：

   ```bash
   git checkout main && git pull --ff-only
   git tag -a v1.2.0 -m v1.2.0 && git push origin v1.2.0
   ```

   tag 只能是 `v<MAJOR.MINOR.PATCH>`（MAJOR ≥ 1）；其他命名不会触发构建。
4. **等自动构建，不要再手动启动 `go-im`**。`go-im-tags` 最多 3 分钟内发现新 tag，以 `TAG=v1.2.0` 启动 `go-im`（全端约 12 分钟）。再手动以同一 TAG 启动会重复构建、覆盖 Release 产物并再次部署。
5. **核对**：
   - [GitHub Release](https://github.com/chaishuanzhu/GO-IM/releases) `v1.2.0`：APK / DMG / MSI / DEB / IPA + `SHA256SUMS.txt`（发布脚本见 [kmp/ci/publish-github-release.sh](../../kmp/ci/publish-github-release.sh)，说明自动生成）
   - Docker Hub `go-im-{gateway,logic,web}`：`1.2.0` 与 `latest` 标签，Overview 为新文档（[deploy/dockerhub/sync-readme.sh](../../deploy/dockerhub/sync-readme.sh) 同步，失败只告警不影响构建，需看构建日志里的「overview 已更新」）
   - 测试环境 <https://test-go-im.chaisz.com/health> 正常，三个 Deployment 镜像为 `1.2.0`
   - 落地页（[site/](../../site/)）显示「当前版本 1.2.0」：页面运行时读取最新的 `v*` Release，无需为发布重新部署

改了本目录的任务脚本时，需同步到 Jenkins 任务配置才会生效（见上文），打 tag 不会更新 Jenkins 上的脚本。

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
