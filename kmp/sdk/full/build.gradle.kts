plugins {
    alias(libs.plugins.goim.kmp.compose)
    alias(libs.plugins.kotlinSerialization)
    id("maven-publish")
}

group = "com.goim"
version = rootProject.findProperty("goim.sdk.version")?.toString() ?: "0.1.0-SNAPSHOT"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.sdk.lite)
            api(projects.ui.designsystem)
            api(projects.ui.featureConversations)
            api(projects.ui.featureChat)
            api(projects.ui.featureContacts)
            api(projects.ui.featureSearch)
            api(projects.ui.featureSettings)
            implementation(projects.ui.featureAuth)
            implementation(projects.core.domain)
            implementation(projects.data.repository)
            implementation(projects.data.network)
            implementation(projects.platform)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kermit)
            implementation(libs.coil.compose)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = when {
            name.contains("kotlinMultiplatform", ignoreCase = true) -> "sdk-full"
            else -> "sdk-full-$name"
        }
        pom {
            name.set("GO-IM SDK Full")
            description.set("GO-IM client SDK with default Compose UI and replaceable slots.")
        }
    }
}
