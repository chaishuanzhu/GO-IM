import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 仅用于 `:sdk:core` / `:sdk:uikit`：
 * - desktop JAR / android AAR 内嵌工程模块 class（Android/Desktop 运行时自包含）
 * - KMP / iOS 的 Gradle Module Metadata **保留**对中间模块的依赖（中间模块发 metadata+iOS，不发 Android）
 * - fat 的 androidRelease / desktop：POM 与 .module 去掉 com.goim 工程坐标，
 *   并把内嵌模块的一阶第三方依赖摊平写回（Kermit / Ktor 等），避免 Duplicate class 与 ClassNotFound
 */
internal fun Project.configureFatSdkPublish() {
    val sdkProject = this

    tasks.withType<Jar>().configureEach {
        if (name != "desktopJar") return@configureEach
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            projectDependencyFiles(listOf("desktopRuntimeClasspath", "desktopCompileClasspath"))
                .filter { it.extension.equals("jar", ignoreCase = true) && it.exists() }
                .map { zipTree(it) }
        })
    }

    // Rewrite fat Android/Desktop module metadata only — keep KMP/iOS metadata intact.
    tasks.withType<GenerateModuleMetadata>().configureEach {
        val androidFat = name.contains("AndroidRelease", ignoreCase = true)
        val desktopFat = name.contains("Desktop", ignoreCase = true) &&
            !name.contains("KotlinMultiplatform", ignoreCase = true)
        if (!androidFat && !desktopFat) return@configureEach
        val configurationName = if (androidFat) "releaseRuntimeClasspath" else "desktopRuntimeClasspath"
        doLast {
            val out = outputs.files.singleFile
            if (!out.isFile) return@doLast
            val external = sdkProject.fatExternalMavenDeps(configurationName)
            out.writeText(rewriteFatModuleMetadata(out.readText(), external))
        }
    }

    afterEvaluate {
        val bundle = tasks.findByName("bundleReleaseAar") ?: return@afterEvaluate
        val embedProjects = fatEmbedProjects()
        val jarTaskPaths = embedProjects.map { "${it.path}:bundleLibRuntimeToJarRelease" }
        bundle.dependsOn(jarTaskPaths)
        bundle.doLast {
            val aar = bundle.outputs.files.singleFile
            val jars = embedProjects.mapNotNull { dep ->
                dep.tasks.findByName("bundleLibRuntimeToJarRelease")
                    ?.outputs?.files?.files
                    ?.firstOrNull { it.extension.equals("jar", ignoreCase = true) && it.exists() }
            }.distinct()
            logger.lifecycle(
                "Fat AAR embed ${jars.size}/${embedProjects.size} project jars into ${aar.name} " +
                    "(missing: ${embedProjects.map { it.path }.filter { path ->
                        jars.none { it.path.contains(path.removePrefix(":").replace(':', '/')) } &&
                            jars.none { it.path.contains(path.substringAfterLast(':')) }
                    }})",
            )
            require(jars.size >= embedProjects.size / 2) {
                "Fat AAR for ${project.path}: only ${jars.size} jars for ${embedProjects.map { it.path }}"
            }
            val fat = File(bundle.temporaryDir, "${project.name}-fat.aar")
            mergeAarWithProjectDeps(aar, jars, fat)
            fat.copyTo(aar, overwrite = true)
        }
    }

    pluginManager.withPlugin("maven-publish") {
        afterEvaluate {
            val baseArtifact = path.removePrefix(":").replace(':', '-')
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    artifactId = when {
                        name.contains("kotlinMultiplatform", ignoreCase = true) -> baseArtifact
                        name.equals("androidRelease", ignoreCase = true) -> "$baseArtifact-android"
                        else -> "$baseArtifact-$name"
                    }
                    when {
                        name.equals("androidRelease", ignoreCase = true) ->
                            rewriteFatPomDependencies { fatExternalMavenDeps("releaseRuntimeClasspath") }
                        name.equals("desktop", ignoreCase = true) ->
                            rewriteFatPomDependencies { fatExternalMavenDeps("desktopRuntimeClasspath") }
                    }
                }
            }
        }
    }
}

private data class ExternalMavenDep(val group: String, val module: String, val version: String)

/**
 * First-level Maven deps of this entrypoint + every embedded project on [configurationName].
 * (Not the full transitive closure — Gradle still resolves transitives for consumers.)
 */
private fun Project.fatExternalMavenDeps(configurationName: String): List<ExternalMavenDep> {
    val configuration = configurations.findByName(configurationName) ?: return emptyList()
    val embedPaths = (fatEmbedProjects().map { it.path } + path).toSet()
    val seen = linkedSetOf<String>()
    val out = mutableListOf<ExternalMavenDep>()
    configuration.incoming.resolutionResult.allDependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .forEach { result ->
            val from = result.from.id as? ProjectComponentIdentifier ?: return@forEach
            if (from.projectPath !in embedPaths) return@forEach
            val requested = result.requested as? ModuleComponentSelector ?: return@forEach
            if (requested.group == "com.goim" || requested.group.startsWith("goim-kmp")) return@forEach
            val key = "${requested.group}:${requested.module}"
            if (!seen.add(key)) return@forEach
            val version = (result.selected.id as? ModuleComponentIdentifier)?.version
                ?.takeIf { it.isNotBlank() && it != "unspecified" }
                ?: requested.version.takeIf { it.isNotBlank() && it != "unspecified" }
                ?: return@forEach
            out += ExternalMavenDep(requested.group, requested.module, version)
        }
    return out
}

/** Drop com.goim deps; ensure flattened third-party deps are present on runtime variants. */
@Suppress("UNCHECKED_CAST")
private fun rewriteFatModuleMetadata(json: String, external: List<ExternalMavenDep>): String {
    val root = groovy.json.JsonSlurper().parseText(json) as MutableMap<String, Any?>
    val variants = root["variants"] as? MutableList<MutableMap<String, Any?>> ?: return json
    for (variant in variants) {
        val name = variant["name"] as? String ?: continue
        val isRuntime = name.contains("Runtime", ignoreCase = true)
        val isApi = name.contains("Api", ignoreCase = true)
        if (!isRuntime && !isApi) continue

        val deps = (variant["dependencies"] as? MutableList<MutableMap<String, Any?>>)
            ?: mutableListOf<MutableMap<String, Any?>>().also { variant["dependencies"] = it }

        deps.removeAll { dep ->
            val group = dep["group"] as? String ?: return@removeAll false
            val version = dep["version"] as? Map<*, *>
            val requires = version?.get("requires") as? String
            group == "com.goim" || group.startsWith("goim-kmp") || requires == "unspecified"
        }

        val existing = deps.mapNotNull { dep ->
            val g = dep["group"] as? String ?: return@mapNotNull null
            val m = dep["module"] as? String ?: return@mapNotNull null
            "$g:$m"
        }.toMutableSet()

        if (isRuntime) {
            for (ext in external) {
                val key = "${ext.group}:${ext.module}"
                if (!existing.add(key)) continue
                deps += mutableMapOf(
                    "group" to ext.group,
                    "module" to ext.module,
                    "version" to mutableMapOf("requires" to ext.version),
                )
            }
        }
        if (deps.isEmpty()) variant.remove("dependencies")
    }
    return groovy.json.JsonBuilder(root).toPrettyString() + "\n"
}

/** Resolve external deps lazily inside withXml (publication task execution), not at configuration time. */
private fun MavenPublication.rewriteFatPomDependencies(external: () -> List<ExternalMavenDep>) {
    pom.withXml {
        val rootNode = asNode()
        val depsNode = (
            (rootNode.get("dependencies") as? groovy.util.NodeList)?.firstOrNull() as? groovy.util.Node
        ) ?: (rootNode.appendNode("dependencies") as groovy.util.Node)
        depsNode.children()
            .filterIsInstance<groovy.util.Node>()
            .filter { dep ->
                val group = dep.childText("groupId")
                val version = dep.childText("version")
                group == "com.goim" || group.startsWith("goim-kmp") || version == "unspecified"
            }
            .forEach { depsNode.remove(it) }

        val existing = depsNode.children()
            .filterIsInstance<groovy.util.Node>()
            .map { "${it.childText("groupId")}:${it.childText("artifactId")}" }
            .toMutableSet()

        for (ext in external()) {
            val key = "${ext.group}:${ext.module}"
            if (!existing.add(key)) continue
            val dep = depsNode.appendNode("dependency")
            dep.appendNode("groupId", ext.group)
            dep.appendNode("artifactId", ext.module)
            dep.appendNode("version", ext.version)
            dep.appendNode("scope", "runtime")
        }
    }
}

private fun groovy.util.Node.childText(name: String): String {
    val child = (get(name) as? groovy.util.NodeList)?.firstOrNull() as? groovy.util.Node ?: return ""
    return child.text().trim()
}

private fun Project.projectDependencyFiles(configurationNames: List<String>): List<File> {
    val seen = linkedSetOf<File>()
    for (name in configurationNames) {
        val configuration = configurations.findByName(name) ?: continue
        runCatching {
            configuration.incoming.artifactView {
                componentFilter { id -> id is ProjectComponentIdentifier }
                lenient(true)
            }.files.files.forEach { seen.add(it) }
        }
    }
    return seen.toList()
}

/** core：core/data/platform；uikit：core/data/platform/ui + sdk-core。 */
private fun Project.fatEmbedProjects(): List<Project> {
    fun wanted(sub: Project): Boolean = when (this.path) {
        ":sdk:core" ->
            sub.path.startsWith(":core:") || sub.path.startsWith(":data:") || sub.path == ":platform"
        ":sdk:uikit" ->
            !sub.path.startsWith(":app") && sub.path != path &&
                (sub.path.startsWith(":core:") || sub.path.startsWith(":data:") ||
                    sub.path.startsWith(":ui:") || sub.path == ":platform" || sub.path == ":sdk:core")
        else -> false
    }
    return rootProject.subprojects.filter(::wanted)
}

private fun mergeAarWithProjectDeps(thinAar: File, deps: List<File>, output: File) {
    output.parentFile.mkdirs()
    val tmp = File(output.parentFile, "fat-aar-work-${thinAar.nameWithoutExtension}").also {
        it.deleteRecursively()
        it.mkdirs()
    }
    try {
        val thinDir = File(tmp, "thin").also { unzip(thinAar, it) }
        val classesJar = File(thinDir, "classes.jar")
        val mergedClasses = File(tmp, "merged-classes").also { it.mkdirs() }
        if (classesJar.exists()) unzip(classesJar, mergedClasses)

        for (dep in deps) {
            when {
                dep.extension.equals("aar", ignoreCase = true) -> {
                    val depDir = File(tmp, "dep-${dep.nameWithoutExtension}")
                    unzip(dep, depDir)
                    File(depDir, "classes.jar").takeIf { it.exists() }?.let { unzip(it, mergedClasses) }
                    listOf("jni", "assets", "libs").forEach { name ->
                        val src = File(depDir, name)
                        if (src.isDirectory) src.copyRecursively(File(thinDir, name), overwrite = true)
                    }
                }
                dep.extension.equals("jar", ignoreCase = true) -> unzip(dep, mergedClasses)
            }
        }

        zipDirectory(mergedClasses, classesJar)
        zipDirectory(thinDir, output)
    } finally {
        tmp.deleteRecursively()
    }
}

private fun unzip(zip: File, target: File) {
    target.mkdirs()
    ZipFile(zip).use { zf ->
        zf.entries().asSequence().forEach { entry ->
            val out = File(target, entry.name)
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile.mkdirs()
                zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

private fun zipDirectory(sourceDir: File, zipFile: File) {
    zipFile.parentFile.mkdirs()
    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
        sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = sourceDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            zos.putNextEntry(ZipEntry(relative))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
