plugins {
    alias(libs.plugins.goim.kmp.library)
    alias(libs.plugins.wire)
}

wire {
    sourcePath {
        srcDir(rootProject.file("../api/proto"))
        include("message.proto")
    }
    kotlin {}
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.wire.runtime)
        }
    }
}
