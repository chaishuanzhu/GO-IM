plugins {
    alias(libs.plugins.goim.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.domain)
            api(projects.data.repository)
            implementation(projects.data.network)
            implementation(projects.data.storage)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GO-IM SDK Lite")
            description.set("GO-IM client SDK without Compose UI (session, messages, conversations).")
        }
    }
}
