plugins {
    alias(libs.plugins.goim.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.compose.material.icons.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
        }
    }
}
