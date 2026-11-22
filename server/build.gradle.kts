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
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// `./gradlew :server:jsNodeProductionRun` keeps the board in server/data/board-events.json
// (whatever directory Gradle runs node from), unless AGENT_BOARD_DATA says otherwise.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec>().configureEach {
    if (System.getenv("AGENT_BOARD_DATA").isNullOrBlank()) {
        environment("AGENT_BOARD_DATA", layout.projectDirectory.file("data/board-events.json").asFile.path)
    }
}
