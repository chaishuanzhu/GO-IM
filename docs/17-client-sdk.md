# 17 — 客户端 SDK：发布与接入

GO-IM KMP 客户端拆成两个 Maven 产物，供宿主 App 引入。服务端仍是现有 Gateway；嵌入式接入方用 `/server/v1` 签发 `uid`+`token`（见 [16-integration-guide.md](16-integration-guide.md)）。

| 产物 | 坐标（Group `com.goim`） | 内容 |
|------|--------------------------|------|
| **Lite** | `sdk-lite` | 连接、消息、会话、群、好友、文件、资料；**无 Compose** |
| **Full** | `sdk-full` | Lite + 默认 CMP 主界面 + 可替换插槽 |

- 版本：`kmp/gradle.properties` → `goim.sdk.version`（当前 `0.1.0-SNAPSHOT`）
- API 标记：`@GoImSdkPreview`（0.x，可能不兼容升级）
- 网关：**≥ 2.0.0**（JWT、`device_id` / `platform`、protobuf）

源码模块：`kmp/sdk/lite`、`kmp/sdk/full`；短入口见 [`kmp/sdk/README.md`](../kmp/sdk/README.md)。

---

## 1. 发布

### 环境

在 `kmp/` 目录执行。Gradle Kotlin DSL 需要 **JDK 17 或 21**（JDK 25+ 会编不过脚本）：

```bash
cd kmp
# macOS 示例
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
# 或：export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 改版本号

编辑 `kmp/gradle.properties`：

```properties
goim.sdk.version=0.1.0-SNAPSHOT
# 正式版示例：0.1.0
```

### 发到本机 Maven Local（开发联调）

```bash
./gradlew :sdk:lite:publishToMavenLocal :sdk:full:publishToMavenLocal
```

产物落在 `~/.m2/repository/com/goim/`。KMP 会为各 target 生成 publication，常见坐标：

| 用途 | 坐标示例 |
|------|----------|
| Android | `com.goim:sdk-lite-android:0.1.0-SNAPSHOT` |
| JVM / Desktop | `com.goim:sdk-lite-desktop:0.1.0-SNAPSHOT` |
| iOS arm64 | `com.goim:sdk-lite-iosArm64:0.1.0-SNAPSHOT` |
| iOS sim | `com.goim:sdk-lite-iosSimulatorArm64:0.1.0-SNAPSHOT` |
| KMP 元数据（common） | `com.goim:sdk-lite:0.1.0-SNAPSHOT` |

Full 同理，把 `sdk-lite` 换成 `sdk-full`。

宿主若是 **Kotlin Multiplatform**，依赖根坐标即可，Gradle 会按 target 解析：

```kotlin
implementation("com.goim:sdk-lite:0.1.0-SNAPSHOT")
// 或
implementation("com.goim:sdk-full:0.1.0-SNAPSHOT")
```

并启用 `mavenLocal()`。

### 发到私有 / 远程仓库

当前模块已挂 `maven-publish`，默认只有 Local。要推到公司 Nexus / GitHub Packages 等，在 `kmp/sdk/lite/build.gradle.kts` 与 `kmp/sdk/full/build.gradle.kts` 的 `publishing { }` 里加仓库，例如：

```kotlin
publishing {
    repositories {
        maven {
            name = "goim"
            url = uri(System.getenv("GOIM_MAVEN_URL") ?: "https://maven.example.com/repository/goim/")
            credentials {
                username = System.getenv("GOIM_MAVEN_USER") ?: ""
                password = System.getenv("GOIM_MAVEN_PASSWORD") ?: ""
            }
        }
    }
    // …已有 publications 配置保留
}
```

然后：

```bash
export GOIM_MAVEN_URL=...
export GOIM_MAVEN_USER=...
export GOIM_MAVEN_PASSWORD=...
./gradlew :sdk:lite:publish :sdk:full:publish
```

正式发版建议：去掉 `-SNAPSHOT`、打 Git tag，与网关版本在 changelog 里对齐。

---

## 2. 宿主工程依赖

### settings / repositories

```kotlin
// settings.gradle.kts 或 dependencyResolutionManagement
repositories {
    mavenLocal()          // 本地联调
    mavenCentral()
    // maven { url = uri("https://your-private-repo/...") }  // 远程发布后
    google()
}
```

### Android-only 宿主

```kotlin
dependencies {
    implementation("com.goim:sdk-lite-android:0.1.0-SNAPSHOT")
    // 要默认 UI：
    // implementation("com.goim:sdk-full-android:0.1.0-SNAPSHOT")
}
```

`PlatformContext`：Android 上为应用 `Context`（见 `com.goim.kmp.common.PlatformContext`）。

### KMP 宿主

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.goim:sdk-lite:0.1.0-SNAPSHOT")
            // 或 implementation("com.goim:sdk-full:0.1.0-SNAPSHOT")
        }
    }
}
```

Full 会带上 Compose Multiplatform + Coil，体积明显大于 Lite。

---

## 3. 整体流程（嵌入式）

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
         Lite：自绘 UI              Full：GoImNavHost(+ slots)
```

令牌签发、签名、webhook 细节见 [16-integration-guide.md](16-integration-guide.md)。SDK **不包含**宿主账号登录 / 注册 UI。

---

## 4. Lite 使用（自绘 UI）

```kotlin
@OptIn(GoImSdkPreview::class)
suspend fun startIm(context: PlatformContext, uid: String, token: String) {
    val client = GoImClient.create(
        GoImConfig(
            serverUrl = "https://im.example.com", // 网关 HTTP 根，无尾斜杠也可
            deviceId = hostStableDeviceId,          // 建议宿主持久化；空则 SDK 生成
            // transport 默认 WebSocket
        ),
        context,
    )

    val session = client.openSession(
        GoImIdentity(uid = uid, token = token, nickname = "可选昵称"),
        persistCredentials = true, // true：可 client.restore()；嵌入式可 false 自管令牌
    )

    // 会话列表
    session.conversations.observeConversations().collect { list -> /* 更新 UI */ }

    // 发文本（单聊 peer = 对方 uid）
    session.sendText(peer = "100001", text = "hi")

    // 踢下线 / 令牌失效：宿主必须 close + 重新拿 token 再 open
    client.sessionEnded.collect { reason ->
        when (reason) {
            SessionEndReason.AUTH_EXPIRED,
            SessionEndReason.KICKED,
            SessionEndReason.ACCOUNT_DISABLED,
            SessionEndReason.ACCOUNT_DELETED -> {
                client.closeSession()
                // 重新走业务登录 / users/token → openSession
            }
            else -> Unit
        }
    }
}
```

`GoImSession` 还暴露：`messages`、`friends`、`groups`、`files`、`profiles`、`stickers`、`search`、`connection`，以及 `onForeground()` / `onBackground()`（前台补连）。

约束：

- 不要依赖 `ui.*`；文件发送传入已选字节 / 路径，权限由宿主申请。
- 冷启动：`client.restore()`（仅当上次 `persistCredentials=true`）。

示范代码：`kmp/app/shared/.../ExternalIdentitySample.kt`。

---

## 5. Full 使用（默认 UI + 插槽）

先 `openSession`（或 `restore`），再嵌入导航：

```kotlin
@OptIn(GoImSdkPreview::class)
@Composable
fun HostImTab(client: GoImClient) {
    GoImNavHost(
        client = client,
        modifier = Modifier.fillMaxSize(),
        slots = GoImUiSlots(
            // null = 用内置默认；按需覆盖
            messageBubble = { msg, ctx -> MyBubble(msg, ctx) },
            conversationRow = { row, actions -> MyRow(row, actions) },
            chatTopBar = { bar, actions -> MyTopBar(bar, actions) },
            emptyConversations = { EmptyHint("还没有聊天") },
            emptyChat = { EmptyHint("选一个会话") },
            composerTrailing = { IconButton(onClick = { /* 业务入口 */ }) { … } },
        ),
        onExit = { /* 宿主关闭 IM Tab */ },
    )
}
```

已有 `UserSession`（例如自有 App 登录后）可直接：

```kotlin
GoImSessionNavHost(session = userSession, slots = GoImUiSlots())
```

### 可替换插槽

| Slot | 模型 | 用途 |
|------|------|------|
| `conversationRow` | `ConversationUi` | 会话行布局 / 角标 |
| `messageBubble` | `MessageUi` | 品牌气泡 |
| `chatTopBar` | `ChatTopBarUi` | 聊天顶栏业务按钮 |
| `emptyConversations` / `emptyChat` | — | 空态文案 / 插画 |
| `composerTrailing` | — | 输入区尾部业务入口 |

Slot 只用稳定 UI 模型（`com.goim.kmp.designsystem`），不要绑 Room / proto。

---

## 6. 与 GO-IM 自有 App

`kmp/app/shared` 依赖 `sdk-full`：

- 登录 / 注册：`AuthNavHost`（App 自有）
- 登录成功：`SessionManager.openSession` → `GoImSessionNavHost`
- 外部 token 旁路：`ExternalIdentitySample`

这是 Full SDK 的参考宿主，不对外发布 `app/*`。

---

## 7. 常见问题

**Q: 只要消息能力、自己画 UI？**  
用 Lite，不要引 Full。

**Q: Token 过期怎么办？**  
监听 `sessionEnded` → `closeSession` → 业务后端重新 `users/token` → `openSession`。

**Q: 多设备互踢？**  
建连上报 `device_id` + `platform`；同一 `device_id` 重连会顶掉自己的旧连接。策略由网关 `multi_device` / 管理台决定。

**Q: 和 Paircast 什么关系？**  
服务端继续用 `pkg/imserver`；客户端用 Lite 自绘即可，不必引 Full。
