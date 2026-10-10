plugins {
    alias(libs.plugins.goim.kmp.compose)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.goim.kmp.shared")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.common)
            implementation(projects.core.domain)
            implementation(projects.data.repository)
            implementation(projects.platform)
            implementation(projects.ui.designsystem)
            implementation(projects.ui.featureAuth)
            implementation(projects.ui.featureConversations)
            implementation(projects.ui.featureChat)
            implementation(projects.ui.featureSettings)
            implementation(projects.ui.featureContacts)
            implementation(projects.ui.featureSearch)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kermit)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
    }
}
