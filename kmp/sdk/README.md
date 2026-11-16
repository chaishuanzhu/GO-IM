# GO-IM Client SDK

| 模块 | Maven | 说明 |
|------|--------|------|
| `:sdk:lite` | `com.goim:sdk-lite` | `GoImClient` / `GoImSession`，无 UI |
| `:sdk:full` | `com.goim:sdk-full` | `GoImNavHost` + `GoImUiSlots` |

完整说明：**[docs/17-client-sdk.md](../../docs/17-client-sdk.md)**  
服务端签发 token：**[docs/16-integration-guide.md](../../docs/16-integration-guide.md)**

## 发布到 GitHub Packages

推荐（CI，自带 `packages: write`）：

```bash
gh workflow run publish-kmp-sdk.yml --repo chaishuanzhu/GO-IM
```

本机（需 JDK 21 + `write:packages`）：

```bash
cd kmp
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export GITHUB_ACTOR=chaishuanzhu
export GITHUB_TOKEN=$(gh auth token)
./gradlew publish
```

包浏览：<https://github.com/chaishuanzhu/GO-IM/packages>

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

implementation("com.goim:sdk-lite:0.1.0-SNAPSHOT")
// implementation("com.goim:sdk-full:0.1.0-SNAPSHOT")
```
