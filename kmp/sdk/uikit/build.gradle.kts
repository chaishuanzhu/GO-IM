plugins {
    alias(libs.plugins.goim.kmp.compose)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Same-version Core is required; hosts depending on UIKit need not declare Core.
            api(projects.sdk.core)
            api(projects.ui.designsystem)
            api(projects.ui.featureConversations)
            api(projects.ui.featureChat)
            api(projects.ui.featureContacts)
            api(projects.ui.featureSearch)
            api(projects.ui.featureSettings)

            // Compose / navigation appear on the UIKit API surface (slots, NavHost).
            api(libs.androidx.navigation.compose)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.coil.compose)

            // Auth feature screens are for the sample app path only — not a public product line.
            implementation(projects.ui.featureAuth)
            implementation(projects.core.domain)
            implementation(projects.data.repository)
            implementation(projects.data.network)
            implementation(projects.platform)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kermit)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GO-IM SDK UIKit")
            description.set(
                "GO-IM UIKit: default Compose Multiplatform chat UI and replaceable slots on top of sdk-core. " +
                    "Does not include host account login/registration.",
            )
        }
    }
}
