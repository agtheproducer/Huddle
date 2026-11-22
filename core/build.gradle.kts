plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)

    // The board engine runs wherever Kotlin runs. The JVM target is what the IDE
    // plugin, the MCP server and the CLI consume; the JS target is the same engine
    // compiled for the browser.
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    // The same board engine on iPhone: the BOB mobile app (module :mobile) reads and posts through it.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
