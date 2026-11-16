# GO-IM Client SDK

| 模块 | Maven | 说明 |
|------|--------|------|
| `:sdk:lite` | `com.goim:sdk-lite` | fat：业务内核，无 UI |
| `:sdk:full` | `com.goim:sdk-full` | fat：默认 CMP UI + 插槽 |

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
./gradlew :sdk:lite:publish :sdk:full:publish
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

implementation("com.goim:sdk-lite:0.2.0-SNAPSHOT")
// 或 implementation("com.goim:sdk-full:0.2.0-SNAPSHOT")
```

## Signal（Lite）

```kotlin
session.sendSignal(to = "g_room", type = "paircast.playback", dataJson = """{"seq":1}""")
session.signals.collect { /* InboundEvent.Signal */ }
// 全量入站：session.connection.inboundEvents（多订阅）
```
