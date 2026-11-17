# 17 — 客户端 SDK：Core / UIKit

GO-IM KMP 客户端按业界主流拆成 **两个 Maven 产物**：无 UI 的 **Core**，以及依赖 Core 的 **UIKit**。服务端仍是现有 Gateway；嵌入式接入方用 `/server/v1` 签发 `uid`+`token`（见 [16-integration-guide.md](16-integration-guide.md)）。

| 产物 | 坐标（Group `com.goim`） | 内容 |
|------|--------------------------|------|
| **Core** | `sdk-core` | 连接、消息、会话、群、好友、文件、资料、Signal；**无 Compose** |
| **UIKit** | `sdk-uikit` | 同版本 Core + 默认 CMP 主界面 + 可替换插槽 |

- 版本：`kmp/gradle.properties` → `goim.sdk.version`（当前 `0.2.0-SNAPSHOT`）；**Core 与 UIKit 同版本发布**
- API 标记：`@GoImSdkPreview`（0.x，可能不兼容升级）
- 包名：`com.goim.sdk.core` / `com.goim.sdk.uikit`
- 网关：**≥ 2.0.0**（JWT、`device_id` / `platform`、protobuf）
- 远程仓：**GitHub Packages** `https://maven.pkg.github.com/chaishuanzhu/GO-IM`
- **只发两个 fat 入口**：`sdk-core` / `sdk-uikit`（android / desktop / KMP metadata）；内部 `core-*`、`data-*`、`ui-*` 打进包内，不再单独发布。第三方依赖由 POM 声明。

源码模块：`kmp/sdk/core`、`kmp/sdk/uikit`；短入口见 [`kmp/sdk/README.md`](../kmp/sdk/README.md)。

> 迁移：原 `sdk-lite` / `sdk-full`（包名 `com.goim.sdk.lite` / `com.goim.sdk.full`）已重命名为上表坐标。0.2.0-SNAPSHOT 起请改依赖与 import。

---

## 1. 依赖边界（接入方怎么接）

### Core 带什么 / 不带什么

| | 说明 |
|--|------|
| 传递 / 公开 | 领域模型、会话与消息 API、`kotlinx-coroutines`（Flow） |
| 实现细节（fat 内嵌） | 网络、本地库、平台存储 |
| **不含** | Compose / 导航 / 图片加载 UI、登录注册页、FCM/APNs 厂商 SDK |

### UIKit 带什么 / 不带什么

| | 说明 |
|--|------|
| 硬依赖 | **同版本** `sdk-core`（声明 UIKit 即可，不必再手写 Core） |
| 公开 UI 面 | Compose Navigation / Lifecycle、Coil、DesignSystem、会话/聊天等 feature |
| 实现细节 | Koin、Kermit、平台桥；示范用 Auth 屏（`implementation`，非产品线） |
| **不含** | 宿主账号登录/注册、推送厂商 SDK、第二套 Core |

### 接入方责任

1. **自绘 UI**：只 `implementation("com.goim:sdk-core:…")`，自己画界面；token 与推送自备。  
2. **要默认界面**：只 `implementation("com.goim:sdk-uikit:…")`，满足 Compose 工具链下界；登录页自建后 `openSession`。  
3. **不要**同时引入两个不同 minor 的 Core。  
4. 依赖冲突时先升到文档已验证版本，再考虑 exclude。  
5. 推送、登录、统计分析永远是宿主依赖。

---

## 2. 发布

### 改版本号

编辑 `kmp/gradle.properties`：

```properties
goim.sdk.version=0.2.0-SNAPSHOT
# 正式版示例：0.2.0
```

### 推荐：GitHub Actions → GitHub Packages

仓库工作流 [`.github/workflows/publish-kmp-sdk.yml`](../.github/workflows/publish-kmp-sdk.yml)（`workflow_dispatch`）：

```bash
gh workflow run publish-kmp-sdk.yml --repo chaishuanzhu/GO-IM
# 可选覆盖版本：
# gh workflow run publish-kmp-sdk.yml -f version=0.1.0 --repo chaishuanzhu/GO-IM
gh run watch --repo chaishuanzhu/GO-IM
```

Actions 使用仓库 `GITHUB_TOKEN`（`packages: write`），无需本机 `write:packages`。包列表：<https://github.com/chaishuanzhu/GO-IM/packages>。

### 本机发布

Gradle Kotlin DSL 需要 **JDK 17/21**（勿用 25+）：

```bash
cd kmp
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"

# 本机 ~/.m2（只发两个入口）
./gradlew :sdk:core:publishToMavenLocal :sdk:uikit:publishToMavenLocal

# 推到 GitHub Packages：需要 classic PAT / gh token 含 write:packages
export GITHUB_ACTOR=chaishuanzhu
export GITHUB_TOKEN=$(gh auth token)   # 或 PAT
# 若缺少 scope：gh auth refresh -h github.com -s write:packages
./gradlew :sdk:core:publish :sdk:uikit:publish
```

约定插件只给 `:sdk:core` / `:sdk:uikit` 挂发布，并打 fat 包；设置了 `GITHUB_ACTOR` + `GITHUB_TOKEN`（或 `GH_TOKEN`）时写入 GitHub Packages。

### 坐标示例

| 用途 | 坐标 |
|------|------|
| KMP common | `com.goim:sdk-core:0.2.0-SNAPSHOT` |
| Android | `com.goim:sdk-core-android:0.2.0-SNAPSHOT` |
| Desktop JVM | `com.goim:sdk-core-desktop:0.2.0-SNAPSHOT` |

UIKit 将 `sdk-core` 换成 `sdk-uikit`。正式发版建议去掉 `-SNAPSHOT` 并打 Git tag。

---

## 3. 宿主工程依赖

### repositories

```kotlin
repositories {
    mavenCentral()
    google()
    maven {
        url = uri("https://maven.pkg.github.com/chaishuanzhu/GO-IM")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: ""
            // 读包：classic PAT 需 read:packages（公开仓库有时仍要求登录）
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
    // 本地联调可加：mavenLocal()
}
```

### 依赖

```kotlin
// KMP commonMain，或 Android 用 sdk-*-android
implementation("com.goim:sdk-core:0.2.0-SNAPSHOT")
// implementation("com.goim:sdk-uikit:0.2.0-SNAPSHOT")
```

本地 composite（替换 Maven）：

```kotlin
includeBuild("../GO-IM/kmp") {
    dependencySubstitution {
        substitute(module("com.goim:sdk-core")).using(project(":sdk:core"))
        // substitute(module("com.goim:sdk-uikit")).using(project(":sdk:uikit"))
    }
}
```

---

## 4. 整体流程（嵌入式）

```
宿主登录 ──▶ 业务后端 ──▶ POST /server/v1/users/token
                              │
                              ▼
                     { uid, token, … }
                              │
                              ▼
              GoImClient.openSession(GoImIdentity(uid, token))
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
         Core：自绘 UI              UIKit：GoImNavHost(+ slots)
```

令牌签发见 [16-integration-guide.md](16-integration-guide.md)。SDK **不包含**宿主账号登录 / 注册 UI。

---

## 5. Core 使用（自绘 UI）

```kotlin
@OptIn(GoImSdkPreview::class)
suspend fun startIm(context: PlatformContext, uid: String, token: String) {
    val client = GoImClient.create(
        GoImConfig(
            serverUrl = "https://im.example.com",
            deviceId = hostStableDeviceId,
        ),
        context,
    )

    val session = client.openSession(
        GoImIdentity(uid = uid, token = token, nickname = "可选昵称"),
        persistCredentials = true,
    )

    session.conversations.observeConversations().collect { /* UI */ }
    session.sendText(peer = "100001", text = "hi")

    // CmdSignal：在线-only，不落库；connection.inboundEvents 为全量 raw（可多订阅）
    session.sendSignal(
        to = "g_room",
        type = "paircast.playback",
        dataJson = """{"action":"pause","seq":3}""",
    )
    session.signals.collect { signal ->
        if (signal.type == "paircast.room_updated") { /* 解析 signal.dataJson */ }
    }

    client.sessionEnded.collect { reason ->
        when (reason) {
            SessionEndReason.AUTH_EXPIRED,
            SessionEndReason.KICKED,
            SessionEndReason.ACCOUNT_DISABLED,
            SessionEndReason.ACCOUNT_DELETED -> {
                client.closeSession()
                // 重新 users/token → openSession
            }
            else -> Unit
        }
    }
}
```

示范：`kmp/app/shared/.../ExternalIdentitySample.kt`。

---

## 6. UIKit 使用（默认 UI + 插槽）

```kotlin
@OptIn(GoImSdkPreview::class)
@Composable
fun HostImTab(client: GoImClient) {
    GoImNavHost(
        client = client,
        slots = GoImUiSlots(
            messageBubble = { msg, ctx -> MyBubble(msg, ctx) },
            conversationRow = { row, actions -> MyRow(row, actions) },
            chatTopBar = { bar, actions -> MyTopBar(bar, actions) },
        ),
        onExit = { /* 关闭 IM */ },
    )
}
```

或已有 `UserSession`：`GoImSessionNavHost(session, slots = GoImUiSlots())`。

可替换插槽（`null` = 默认）：`conversationRow`、`messageBubble`、`chatTopBar`、`emptyConversations`、`emptyChat`、`composerTrailing`。

---

## 7. 与 GO-IM 自有 App

`kmp/app/shared` 依赖 `sdk-uikit`：登录 UI 自有（`AuthNavHost`，不随 SDK 产品线对外承诺），主区 `GoImSessionNavHost`；外部 token 见 `ExternalIdentitySample`。`app/*` 不对外发布。

---

## 8. 常见问题

**Q: 只要消息能力？** 用 Core，不要引 UIKit。  
**Q: Token 过期？** `sessionEnded` → `closeSession` → 重新 `users/token` → `openSession`。  
**Q: 本机 publish 401？** Token 缺 `write:packages`，用 Actions 发布或 `gh auth refresh -h github.com -s write:packages`。  
**Q: 和 Paircast？** 服务端 `pkg/imserver`；客户端 Core 自绘 + `sendSignal` / `signals`（如 `paircast.playback`）。本地联调可用 `includeBuild("../GO-IM/kmp")` 替换 Maven `com.goim:sdk-core`（含 iOS）。  
**Q: Signal？** `session.sendSignal(to, type, dataJson)` / `session.signals`；内容 `{"type","data"}`，无 ACK/离线/未读。`connection.inboundEvents` 可多订阅。  
**Q: 旧坐标？** `sdk-lite`→`sdk-core`，`sdk-full`→`sdk-uikit`；import `com.goim.sdk.lite`→`com.goim.sdk.core`，`com.goim.sdk.full`→`com.goim.sdk.uikit`。
