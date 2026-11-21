plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js {
        nodejs {
            binaries.executable()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
