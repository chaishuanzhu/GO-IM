# GO-IM Client SDK

对外对外入口按业界主流拆成两档：Core（无 UI）与 UIKit（有 UI）。

| 模块 | Maven | 说明 |
|------|--------|------|
| `:sdk:core` | `com.goim:sdk-core` | fat：连接 / 消息 / 会话 / 群友 / 媒体；**无 Compose** |
| `:sdk:uikit` | `com.goim:sdk-uikit` | fat：依赖同版本 Core + 默认 CMP UI + 可替换插槽 |

完整说明：**[docs/17-client-sdk.md](../../docs/17-client-sdk.md)**  
服务端签发 token：**[docs/16-integration-guide.md](../../docs/16-integration-guide.md)**

## 发布到 GitHub Packages

只发上述两个入口（内部模块已打进 AAR/JAR）：

```bash
gh workflow run publish-kmp-sdk.yml --repo chaishuanzhu/GO-IM
```

本机（JDK 21 + `write:packages`）：

```bash
cd kmp
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export GITHUB_ACTOR=chaishuanzhu
export GITHUB_TOKEN=$(gh auth token)
./gradlew :sdk:core:publish :sdk:uikit:publish
```

当前版本：`0.2.0-SNAPSHOT`（android / desktop / KMP metadata）。

## 宿主依赖

```kotlin
repositories {
    mavenCentral()
    google()
    maven {
        url = uri("https://maven.pkg.github.com/chaishuanzhu/GO-IM")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN") // read:packages
        }
    }
}

// 自绘 UI：只引 Core
implementation("com.goim:sdk-core:0.2.0-SNAPSHOT")
// 默认界面：只引 UIKit（传递同版本 Core）
// implementation("com.goim:sdk-uikit:0.2.0-SNAPSHOT")
```

## Signal（Core）

```kotlin
session.sendSignal(to = "g_room", type = "paircast.playback", dataJson = """{"seq":1}""")
session.signals.collect { /* InboundEvent.Signal */ }
// 全量入站：session.connection.inboundEvents（多订阅）
```
