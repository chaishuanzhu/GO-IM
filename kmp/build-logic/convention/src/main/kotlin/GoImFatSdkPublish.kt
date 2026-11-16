import org.gradle.api.Project
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
 * 仅用于 `:sdk:lite` / `:sdk:full`：
 * - desktop JAR / android AAR 内嵌工程模块 class
 * - POM 去掉工程坐标（`com.goim` / `goim-kmp.*` / version=unspecified）
 * - 关闭 Gradle Module Metadata，避免再解析中间包
 */
internal fun Project.configureFatSdkPublish() {
    tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

    tasks.withType<Jar>().configureEach {
        if (name != "desktopJar") return@configureEach
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            projectDependencyFiles(listOf("desktopRuntimeClasspath", "desktopCompileClasspath"))
                .filter { it.extension.equals("jar", ignoreCase = true) && it.exists() }
                .map { zipTree(it) }
        })
    }

    tasks.matching { it.name == "bundleReleaseAar" }.configureEach {
        val androidJars = provider {
            collectAndroidProjectJars().filter { it.exists() }
        }
        inputs.files(androidJars)
        doLast {
            val aar = outputs.files.singleFile
            val jars = androidJars.get()
            logger.lifecycle("Fat AAR embed ${jars.size} project jars into ${aar.name}")
            val fat = File(temporaryDir, "${project.name}-fat.aar")
            mergeAarWithProjectDeps(aar, jars, fat)
            fat.copyTo(aar, overwrite = true)
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                stripEmbeddedProjectDependencies()
            }
        }
        afterEvaluate {
            val baseArtifact = path.removePrefix(":").replace(':', '-')
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    artifactId = when {
                        name.contains("kotlinMultiplatform", ignoreCase = true) -> baseArtifact
                        name.equals("androidRelease", ignoreCase = true) -> "$baseArtifact-android"
                        else -> "$baseArtifact-$name"
                    }
                    stripEmbeddedProjectDependencies()
                }
            }
        }
    }
}

private fun MavenPublication.stripEmbeddedProjectDependencies() {
    pom.withXml {
        val depsNode = (asNode().get("dependencies") as? groovy.util.NodeList)
            ?.firstOrNull() as? groovy.util.Node
            ?: return@withXml
        depsNode.children()
            .filterIsInstance<groovy.util.Node>()
            .filter { dep ->
                val group = dep.childText("groupId")
                val version = dep.childText("version")
                group == "com.goim" || group.startsWith("goim-kmp") || version == "unspecified"
            }
            .forEach { depsNode.remove(it) }
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

/** 收集 android runtime 上所有工程依赖的 class jar（bundleLibRuntimeToJarRelease）。 */
private fun Project.collectAndroidProjectJars(): List<File> {
    val classpathName = listOf("androidReleaseRuntimeClasspath", "releaseRuntimeClasspath")
        .firstOrNull { configurations.findByName(it) != null }
        ?: return emptyList()
    val configuration = configurations.getByName(classpathName)
    val projectIds = configuration.incoming.resolutionResult.allDependencies
        .asSequence()
        .filterIsInstance<ResolvedDependencyResult>()
        .map { it.selected.id }
        .filterIsInstance<ProjectComponentIdentifier>()
        .map { it.projectPath }
        .toSet()

    return projectIds.mapNotNull { path ->
        val dep = project(path)
        val jarTask = dep.tasks.findByName("bundleLibRuntimeToJarRelease")
            ?: dep.tasks.findByName("jvmJar")
            ?: dep.tasks.findByName("jar")
        jarTask?.outputs?.files?.files?.firstOrNull { it.extension.equals("jar", true) }
    }
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
