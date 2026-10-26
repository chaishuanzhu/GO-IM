import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.app.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.goim.kmp.desktop.MainKt"
        // 打包用的 JDK 须带 jpackage（Android Studio 的 JBR 没有），CI 通过 -Pgoim.packageJavaHome 指定
        providers.gradleProperty("goim.packageJavaHome").orNull?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "GO-IM"
            // jpackage 要求 MAJOR.MINOR.PATCH 且 MAJOR > 0
            packageVersion = providers.gradleProperty("goim.versionName").getOrElse("1.0.0")
            // 来自 ./gradlew suggestRuntimeModules
            modules("java.instrument", "java.logging", "java.management", "java.prefs", "jdk.security.auth", "jdk.unsupported")
            macOS {
                bundleID = "com.goim.kmp.desktop"
            }
        }
    }
}
