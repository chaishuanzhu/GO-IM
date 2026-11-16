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
 * SDK 入口（`:sdk:lite` / `:sdk:full`）会额外打成 fat 包；其它库模块默认不发布。
 */
class GoImPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")

        group = "com.goim"
        val sdkVersion = rootProject.findProperty("goim.sdk.version")?.toString()?.takeIf { it.isNotBlank() }
            ?: "0.1.0-SNAPSHOT"
        version = sdkVersion

        val baseArtifact = path.removePrefix(":").replace(':', '-')
        val isSdkEntrypoint = path == ":sdk:lite" || path == ":sdk:full"

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                androidTarget {
                    publishLibraryVariants("release")
                }
            }
        }

        if (isSdkEntrypoint) {
            configureFatSdkPublish()
        }

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                groupId = "com.goim"
                version = sdkVersion
                artifactId = when {
                    name.contains("kotlinMultiplatform", ignoreCase = true) -> baseArtifact
                    name.equals("androidRelease", ignoreCase = true) -> "$baseArtifact-android"
                    else -> "$baseArtifact-$name"
                }
                pom {
                    name.set(baseArtifact)
                    description.set(
                        if (isSdkEntrypoint) {
                            "GO-IM fat SDK artifact $baseArtifact (embeds internal modules)"
                        } else {
                            "GO-IM KMP module $baseArtifact"
                        },
                    )
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

/** 只给 SDK 入口挂发布；中间库不发到 Maven。 */
internal fun Project.applyGoImPublishIfLibrary() {
    if (path != ":sdk:lite" && path != ":sdk:full") return
    pluginManager.apply("goim.kmp.publish")
}
