import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * 将 KMP 库模块发布到 Maven（本机 + 可选 GitHub Packages）。
 *
 * - group `com.goim`，version 来自 `goim.sdk.version`
 * - artifactId：`:sdk:lite` → `sdk-lite`；各 target 为 `sdk-lite-android` 等
 * - 远程仓：`GITHUB_ACTOR` + `GITHUB_TOKEN`（或 `GH_TOKEN`）存在时写入 GitHub Packages
 */
class GoImPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")

        group = "com.goim"
        version = rootProject.findProperty("goim.sdk.version")?.toString() ?: "0.1.0-SNAPSHOT"

        val baseArtifact = path.removePrefix(":").replace(':', '-')

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                androidTarget {
                    publishLibraryVariants("release")
                }
            }
        }

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                artifactId = when {
                    name.contains("kotlinMultiplatform", ignoreCase = true) -> baseArtifact
                    name.equals("androidRelease", ignoreCase = true) -> "$baseArtifact-android"
                    else -> "$baseArtifact-$name"
                }
                pom {
                    name.set(baseArtifact)
                    description.set("GO-IM KMP module $baseArtifact")
                    url.set("https://github.com/chaishuanzhu/GO-IM")
                }
            }

            val token = System.getenv("GITHUB_TOKEN")
                ?: System.getenv("GH_TOKEN")
                ?: ""
            val actor = System.getenv("GITHUB_ACTOR") ?: ""
            if (token.isNotBlank() && actor.isNotBlank()) {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/chaishuanzhu/GO-IM")
                        credentials {
                            username = actor
                            password = token
                        }
                    }
                }
            }
        }
    }
}

/** 非 app 模块才挂发布插件（由 library / compose 约定调用）。 */
internal fun Project.applyGoImPublishIfLibrary() {
    if (path.startsWith(":app:")) return
    pluginManager.apply("goim.kmp.publish")
}
