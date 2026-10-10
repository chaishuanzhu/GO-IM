plugins {
    alias(libs.plugins.goim.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.ui.designsystem)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(projects.platform)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.coil.gif)
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
