# GO-IM KMP 客户端

Kotlin Multiplatform + Compose Multiplatform 跨平台客户端（Android / iOS / Desktop）。架构与里程碑见 [`docs/15-kmp-client-architecture.md`](../docs/15-kmp-client-architecture.md)。

当前进度：**M4 打磨与对标收尾**。

- M1 核心链路：登录 / 注册、服务器设置、WS + TCP 双通道、重连 / 心跳 / 被踢 / 登录失效、Room 本地库、会话列表、单聊文本、历史分页、离线消息、设置页（文档 §17）。
- M2 社交：群聊（建群 / 加群 / 群信息 / 邀请 / 退群 / 群通知）、好友（通讯录、请求、接受 / 拒绝、选择器）、未读角标、消息搜索并跳转、删除会话（文档 §18）。
- M3 富媒体：图片（多选 / 原图 / 相机 / 压缩 / 预览）、视频、文件、语音录制与播放、贴纸、Emoji、@ 提及、上传 / 下载进度（文档 §19）。
- M4 打磨：多账号切换、平板 / 桌面三栏、桌面快捷键与拖拽发送、桌面录音（WAV）、开发者工具（日志 / 网络 / 数据库导出）、深色模式、性能与无障碍（文档 §20）。

## 环境

- JDK 17–21 运行 Gradle（Gradle 8.14 不支持 JDK 25）。本机可直接用 Android Studio 自带 JBR：

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

- Android SDK：`local.properties` 写入 `sdk.dir=$HOME/Library/Android/sdk`（不入库）。
- iOS：Xcode + [Tuist](https://tuist.dev)。

## 常用命令

```bash
cd kmp

./gradlew check                                   # Android + Desktop 编译、单测、Lint
./gradlew desktopTest iosSimulatorArm64Test       # Desktop + iOS 模拟器单测
./gradlew :app:android:installDebug               # 安装到已连接的 Android 设备 / 模拟器
./gradlew :app:desktop:run                        # 运行桌面端

cd iosApp && tuist generate                       # 生成 Xcode 工程后用 Xcode 运行 GOIMKMP
```

`iosApp` 的预构建脚本会调用 `./gradlew :app:shared:embedAndSignAppleFrameworkForXcode`；Xcode 环境没有 `JAVA_HOME` 时回退到 Android Studio JBR。

默认服务器为 `https://im.chaisz.com`；登录页右上角齿轮可改为本地网关（Android 模拟器用 `http://10.0.2.2:8080`）。

## 桌面快捷键

⌘/Ctrl+1…4 切换 Tab，⌘/Ctrl+F 搜索，Esc 关闭右栏聊天，Enter 发送、Shift+Enter 换行；文件可直接拖进聊天区发送。

## 对本地网关联调

网关需同时开启 WS 与 TCP（`"transport": "both"`）。不依赖 Docker 时可用纯内存配置（MySQL / Redis / Kafka 均关闭，服务端历史不可用）：

```bash
# 仓库根目录
CONFIG_PATH=/path/to/config.json go run ./cmd/gateway/

# kmp/：WS 与 TCP 互发、离线补齐、传输切换；群组全流程、好友 / 搜索降级；图片 / 语音 / 贴纸收发与下载
GOIM_E2E=1 ./gradlew :data:repository:desktopTest --tests '*GatewayE2ETest*'

# 断线重连：运行期间手动重启一次网关
GOIM_E2E_RECONNECT=1 ./gradlew :data:repository:desktopTest --tests '*reconnectsAfterGatewayRestart*'
```

可选 `GOIM_E2E_URL` 指定网关地址（默认 `http://127.0.0.1:8080`）。

## 模块

| 模块 | 说明 |
|------|------|
| `build-logic` | Convention plugins：`goim.kmp.library`（Android + Desktop JVM + iOS arm64 / simulator arm64）、`goim.kmp.compose` |
| `core:common` | `PlatformContext`、`ioDispatcher`、`DomainError`、时间、`LogBuffer`（开发者工具日志） |
| `core:model` | 领域模型、会话 ID 规则、消息预览文案 |
| `core:domain` | Repository 接口、入站事件 / 出站信封、UseCase |
| `data:proto` | Wire 由 `../api/proto/message.proto` 生成 `im.Message`；`Cmd` / `ChatType` / `MsgType` 常量；`MessageCodec`；与 Go 服务端的黄金字节测试 |
| `data:transport` | `IMTransport`：WebSocket（Ktor）/ TCP（ktor-network）；`ReconnectPolicy` |
| `data:pipeline` | Netty 风格 `IMPipeline`、长度帧编解码、Protobuf 编解码、`CmdDispatchHandler`、`OutboundAckTracker` |
| `data:network` | Ktor `HttpClient`、`GoImApi`（登录 / 注册 / token 探测）、`GoImUserApi`（群组 / 好友 / 搜索）、`GoImFileApi`（上传 / 下载）、`StickerCdnApi` |
| `data:storage` | `AppDirectories` / `UserHome`、`device.json`、`SecureStore`（Keychain / EncryptedSharedPreferences / Java Preferences）、`LocalMediaStore`（暂存 / 媒体 / 文件目录） |
| `data:database` | Room KMP v1（schema 导出在 `schemas/`）、DAO、映射 |
| `data:repository` | Connection / Message / Conversation / Auth / Group / Friend / Search / File / Sticker 实现、`SessionCoordinator`、`SessionManager` |
| `platform` | `networkAvailability()`：网络恢复触发重连；图片解码 / JPEG 压缩、录音、语音播放、媒体时长 |
| `ui:designsystem` | `GoImTheme`、设计令牌（`GoImSpacing` / `GoImSize` / `GoImElevation` / 字体，规范见 [designtoken.md](designtoken.md)）、格式化、通用组件、图标 |
| `ui:feature-auth` / `feature-conversations` / `feature-chat` / `feature-settings` | 登录（含已保存账号）、会话列表、聊天、设置 / 服务器设置 / 切换账号 / 开发者工具 |
| `ui:feature-contacts` | 通讯录、新的朋友、群组列表、群信息、好友选择器 |
| `ui:feature-search` | 消息搜索（服务端优先，本机兜底） |
| `app:shared` | Koin 装配、根 Composable `App()`、导航（窄屏底部 Tab、宽屏导航栏 + 三栏）、前台 / 网络恢复补连；iOS `MainViewController()`；导出 `Shared.framework` |
| `app:android` / `app:desktop` | 平台入口（`GoImApplication` / `main` 中 `initKoin`） |
| `iosApp` | Tuist 工程，SwiftUI 承载 `MainViewController()` |
