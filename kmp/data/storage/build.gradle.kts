plugins {
    alias(libs.plugins.goim.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.common)
            api(projects.core.model)
            api(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
