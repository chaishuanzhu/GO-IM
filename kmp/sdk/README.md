# GO-IM Client SDK

| 模块 | Maven | 说明 |
|------|--------|------|
| `:sdk:lite` | `com.goim:sdk-lite` | `GoImClient` / `GoImSession`，无 UI |
| `:sdk:full` | `com.goim:sdk-full` | `GoImNavHost` + `GoImUiSlots` |

完整说明（发布、依赖、Lite/Full 示例、插槽）：**[docs/17-client-sdk.md](../../docs/17-client-sdk.md)**  
服务端签发 token：**[docs/16-integration-guide.md](../../docs/16-integration-guide.md)**

## 发布到本机

```bash
cd kmp
# 需要 JDK 17/21（勿用 25+）
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"

# 改版本：gradle.properties → goim.sdk.version
./gradlew :sdk:lite:publishToMavenLocal :sdk:full:publishToMavenLocal
```

远程仓库：在各模块 `publishing { repositories { … } }` 配置后执行 `:sdk:lite:publish` / `:sdk:full:publish`（见文档 §1）。

## 宿主最小依赖

```kotlin
repositories { mavenLocal(); mavenCentral(); google() }

// KMP commonMain / 或 Android 用 sdk-*-android
implementation("com.goim:sdk-lite:0.1.0-SNAPSHOT")
// implementation("com.goim:sdk-full:0.1.0-SNAPSHOT")
```
