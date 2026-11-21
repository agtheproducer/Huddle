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

// Local stdio MCP server exposing the board to agents (Amit's agent, branch agent/mcp).
include(":mcp")

// IntelliJ IDEA "Agent Board" tool window (Julieta's agent, branch agent/idea-plugin).
include(":idea-plugin")

// Demo commands and replay safety net (Codex, branch agent/core).
include(":cli")
