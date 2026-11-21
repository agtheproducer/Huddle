plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        intellijIdea("2026.2")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.agentboard.plugin"
        name = "Agent Board"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
}

tasks.runIde {
    listOf("AGENT_BOARD_REPO", "AGENT_BOARD_ISSUE", "AGENT_BOARD_AGENT", "AGENT_BOARD_STAGE", "GITHUB_TOKEN").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    // Open this repo straight away so the Agent Board tool window is one click from launch.
    args(rootProject.projectDir.absolutePath)
}
