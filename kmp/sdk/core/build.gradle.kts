plugins {
    alias(libs.plugins.goim.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Public API surface hosts compile against.
            api(projects.core.model)
            api(projects.core.domain)
            api(projects.data.repository)
            api(libs.kotlinx.coroutines.core)

            // Implementation details (fat AAR/JAR embeds project code; POM keeps third-party).
            implementation(projects.data.network)
            implementation(projects.data.storage)
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
            name.set("GO-IM SDK Core")
            description.set(
                "GO-IM Core SDK (no UI): session, messages, conversations, groups, friends, media. " +
                    "Hosts supply account login UI and push vendor SDKs.",
            )
        }
    }
}
