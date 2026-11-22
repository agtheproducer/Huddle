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
    compilerOptions {
        // Call the platform's interface defaults directly instead of compiling copies of them into our
        // classes: without this, the Plugin Verifier reports those copies (ToolWindowFactory.isApplicable,
        // getAnchor, manage...) as our own use of deprecated and experimental API.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    // The IntelliJ test framework's JUnit 5 listener still loads JUnit 4 classes.
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdea("2026.2")
        // The pure logic (wake rules, display rules, settings) is tested without starting an IDE.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "com.agentboard.plugin"
        name = "BOB – Board of Bots"
        version = "0.1.0"
        ideaVersion {
            // Built against 2026.2 and verified on 2026.2 and 2026.3 (below): claim only what was checked.
            sinceBuild = "262"
            untilBuild = "263.*"
        }
    }
    buildSearchableOptions = false

    // ./gradlew :idea-plugin:verifyPlugin: JetBrains' compatibility check (internal/deprecated API use,
    // missing classes) against every IDE version the plugin claims (sinceBuild..untilBuild).
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks.runIde {
    listOf("AGENT_BOARD_REPO", "AGENT_BOARD_ISSUE", "AGENT_BOARD_AGENT", "AGENT_BOARD_STAGE", "AGENT_BOARD_HTTP_URL", "GITHUB_TOKEN").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    // Open this repo straight away so the Agent Board tool window is one click from launch.
    args(rootProject.projectDir.absolutePath)
}
