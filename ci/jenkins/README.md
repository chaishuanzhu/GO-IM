# Jenkins 任务脚本

[jenkins.chaisz.com](https://jenkins.chaisz.com) 上 GO-IM 相关任务的 Pipeline 脚本。任务类型均为「Pipeline script」（脚本存在任务配置里，非 SCM）：Jenkins 控制器到 GitHub 的网络不稳定，检出只在构建节点上做。

**这里的文件是任务配置的副本**：改完脚本后需粘贴到对应任务的配置页（Pipeline → Script）才会生效；在 Jenkins 上直接改了脚本，也请同步回这里。参数和触发器定义在任务配置中，见下表。

| 任务 | 脚本 | 触发 | 作用 |
|------|------|------|------|
| `go-im` | [go-im.groovy](go-im.groovy) | 手动；或由 `go-im-tags` 以 `TAG` 参数启动 | 全端客户端构建（Android / macOS / iOS / Windows / Linux）、服务端镜像推送 Docker Hub、发布 GitHub Release `v<VERSION>`、部署 k8s 测试环境；`TAG` 构建再部署生产环境 |
| `go-im-tags` | [go-im-tags.groovy](go-im-tags.groovy) | 定时轮询 `H/3 * * * *` | 发现新的 `v<MAJOR.MINOR.PATCH>` tag 时启动 `go-im` |
| `go-im-server` | [go-im-server.groovy](go-im-server.groovy) | 手动 | 只构建服务端镜像（tag `sha-<7>`）并部署测试环境 |

## 发布新版本

`go-im` 的所有产物都取自 **tag 指向的提交**：镜像、安装包，以及同步到 Docker Hub Overview 的 `deploy/dockerhub/*.md` + `deploy/README.md`。tag 之后才合入的文档不会出现在这次发布里，只能等下一次发布。因此顺序固定为：**改文档 → 合入 main → 在 main 上打 tag → 自动构建**。

1. **改文档、改版本号**（在功能分支上）。把下列文件中的上一个版本号改成新版本（`rg -n '\b<上一版本>\b' deploy` 可列出）：
   - `deploy/dockerhub/{gateway,logic}.md`：「`X.Y.Z` 等 … GitHub Release `vX.Y.Z`」
   - `deploy/k8s/kustomization.yaml`：两个镜像的 `newTag`
   - `deploy/README.md`、`deploy/docker/docker-compose*.yml`：`GOIM_TAG` 示例
   - 落地页 / Docker Hub 文案如有新功能，一并更新 `site/index.html`、`deploy/dockerhub/*.md`
2. **合入 main 并推送**。main 上 `site/**` 有变更时 Pages 工作流会自动重新部署落地页。
3. **在 GitHub 上的 main 最新提交上打 tag**：

   ```bash
   git checkout main && git pull --ff-only
   git tag -a v1.2.0 -m v1.2.0 && git push origin v1.2.0
   ```

   tag 只能是 `v<MAJOR.MINOR.PATCH>`（MAJOR ≥ 1）；其他命名不会触发构建。
4. **等自动构建，不要再手动启动 `go-im`**。`go-im-tags` 最多 3 分钟内发现新 tag，以 `TAG=v1.2.0` 启动 `go-im`（全端约 12 分钟）。再手动以同一 TAG 启动会重复构建、覆盖 Release 产物并再次部署（含生产）。测试环境部署失败时不会部署生产。
5. **核对**：
   - [GitHub Release](https://github.com/chaishuanzhu/GO-IM/releases) `v1.2.0`：APK / DMG / MSI / DEB / IPA + `SHA256SUMS.txt`（发布脚本见 [kmp/ci/publish-github-release.sh](../../kmp/ci/publish-github-release.sh)，说明自动生成）
   - Docker Hub `go-im-{gateway,logic}`：`1.2.0` 与 `latest` 标签，Overview 为新文档（[deploy/dockerhub/sync-readme.sh](../../deploy/dockerhub/sync-readme.sh) 同步，失败只告警不影响构建，需看构建日志里的「overview 已更新」）
   - 测试环境 <https://test-go-im.chaisz.com/health> 正常，`logic` / `gateway` 两个 Deployment 镜像为 `1.2.0`
   - 生产环境：构建描述以「→ prod」结尾，`Deploy prod` 阶段日志里的健康检查为 `"status":"ok"`（见下文「生产环境」）
   - 落地页（[site/](../../site/)）显示「当前版本 1.2.0」：页面运行时读取最新的 `v*` Release，无需为发布重新部署

改了本目录的任务脚本时，需同步到 Jenkins 任务配置才会生效（见上文），打 tag 不会更新 Jenkins 上的脚本。

## 任务参数

**go-im**：`TAG`（设置后忽略 `BRANCH` / `VERSION`）、`BRANCH`、`VERSION`（MAJOR ≥ 1，DMG / MSI 的限制）、`BUILD_ANDROID`、`BUILD_DESKTOP`（macOS）、`BUILD_IOS`、`BUILD_WINDOWS`、`BUILD_LINUX`、`BUILD_IMAGES`、`DOCKERHUB_NAMESPACE`（留空 = Docker Hub 凭据的用户名）、`PUBLISH`、`PRERELEASE`、`DEPLOY_TEST`、`DEPLOY_PROD`（默认开；只对 `TAG` 构建、且镜像推送到 `870027381` 时生效）

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
| `goim-macos-devid-p12` | Secret file | Developer ID Application 证书 + 私钥（.p12），macOS DMG 签名用（见下文「macOS 签名与公证」） |
| `goim-macos-devid-password` | Secret text | 上面 .p12 的密码 |
| `github-token` | Secret text | 创建 / 编辑 GitHub Release |
| `dockerhub` | Username with password | Docker Hub 用户名 + Read & Write access token |
| `k8s-deployer-kubeconfig` | Secret file | ServiceAccount `jenkins/deployer`（在 `go-im-test` 有 edit 权限） |
| `goim-prod-ssh` | SSH Username with private key | 生产服务器的 root 密钥，只能执行部署脚本（见下文） |
| `goim-prod-target` | Secret file | 生产地址，shell 变量 `PROD_HOST`、`PROD_HOST_KEY`、`PROD_URL`（见下文） |

## macOS 签名与公证

`go-im` 的 macOS 阶段：`createDistributable` 产出未签名的 .app，再由 [kmp/ci/macos-sign-notarize.sh](../../kmp/ci/macos-sign-notarize.sh) 把 `goim-macos-devid-p12` 导入临时钥匙串 → 以 Developer ID + hardened runtime 由内到外签名（含 jar 内的原生库，entitlements 见 `kmp/app/desktop/macos/entitlements.plist`）→ 打 DMG 并签名 → `asc notarization submit --wait` 公证 → `stapler staple` → `spctl` 校验。任何一步失败构建即失败，不会发布未公证的 DMG。

不用 Compose 自带的签名：节点到 Apple 时间戳服务约 3% 的请求会超时，Compose / jpackage 对每个文件只试一次，几十个文件下来几乎必然失败；脚本对每个文件、以及公证上传都会重试。本地验证：`DEVID_P12=… DEVID_P12_PASSWORD=… bash ci/macos-sign-notarize.sh app/desktop/build/compose/binaries/main/app/GO-IM.app /tmp/GO-IM.dmg`（`NOTARIZE=false` 只签名）。

- **公证凭据**：使用 `node-mac` 上 `asc` 已登录的 App Store Connect API Key（Jenkins agent 以同一用户运行），`asc auth status` 可查看。换 Key 后在节点上重新 `asc auth login`。
- **证书**：Developer ID Application 证书只能由团队 Account Holder 在 [Certificates](https://developer.apple.com/account/resources/certificates/add) 创建（API Key 无权创建）。有效期 5 年；更换后重新导出 .p12（证书 + 私钥）更新上面两个凭据。
- **公证失败**：构建日志会打印 `asc notarization log` 的结果；常见原因是未签名的内嵌二进制或缺少 hardened runtime。

## 生产环境

生产服务器的 `/root/docker/go-im`：docker compose 部署 redis / mysql / rustfs / gateway，由服务器上的 Traefik 对外提供 HTTPS。配置与密钥在服务器上的 `config.json`、`.env`（`GOIM_TAG` 即线上版本），地址与密钥都不进仓库。

`go-im` 的 `Deploy prod` 阶段在测试环境部署成功后，用 `goim-prod-ssh` 登录服务器并传入版本号。该公钥在 `authorized_keys` 中带 `command="/root/docker/go-im/deploy.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty`，只能执行部署脚本 [prod-deploy.sh](prod-deploy.sh)：拉取 `870027381/go-im-gateway:<版本>` → 改 `.env` 的 `GOIM_TAG` → 重建 gateway → 容器内健康检查，失败则恢复原版本。之后 Jenkins 再从外部访问 `$PROD_URL/health`。

`goim-prod-target` 的内容（服务器重装后更新 `PROD_HOST_KEY`，取自 `ssh-keyscan -t ed25519 <主机>`）：

```sh
PROD_HOST=<IP 或主机名>
PROD_HOST_KEY='ssh-ed25519 AAAA...'
PROD_URL=https://<生产域名>
```

改了 `prod-deploy.sh` 需手动同步到服务器的 `/root/docker/go-im/deploy.sh`。手动回滚或部署指定版本：在服务器上执行 `/root/docker/go-im/deploy.sh 1.2.0`。
