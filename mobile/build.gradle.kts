plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// BOB on iPhone: a Compose Multiplatform app over the same Kotlin core as the IntelliJ plugin,
// the MCP server and the board server. Xcode builds it through iosApp/ (embedAndSignAppleFrameworkForXcode).
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.resources {
    packageOfResClass = "com.agentboard.mobile.resources"
    publicResClass = false
}
