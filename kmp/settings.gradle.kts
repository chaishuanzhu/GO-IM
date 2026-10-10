rootProject.name = "goim-kmp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":core:common")
include(":core:model")
include(":core:domain")
include(":data:proto")
include(":data:transport")
include(":data:pipeline")
include(":data:network")
include(":data:storage")
include(":data:database")
include(":data:repository")
include(":platform")
include(":ui:designsystem")
include(":ui:feature-auth")
include(":ui:feature-conversations")
include(":ui:feature-chat")
include(":ui:feature-settings")
include(":ui:feature-contacts")
include(":ui:feature-search")
include(":app:shared")
include(":app:android")
include(":app:desktop")
