rootProject.name = "agent-board"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Shared engine: event types, GitHub Issue store, board state, claim/conflict rules (Agent A).
include(":core")

// Add each module here once its owner has created its build.gradle.kts:
// include(":mcp")         // Agent B: local stdio MCP server
// include(":idea-plugin") // Agent C: IntelliJ IDEA "Agent Board" tool window
