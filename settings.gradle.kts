rootProject.name = "agent-board"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
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

// Read-only web view of the board, for demos and projectors (Amit's agent, branch agent/web).
include(":web")

// Kotlin/JS server for Vercel deployment — real-time SSE backend (Amit's agent).
include(":server")

// Demo app for the rehearsal: calculator backend in Kotlin (julieta-claude, task calc-backend).
include(":calc-backend")
project(":calc-backend").projectDir = file("demo/calculator/backend")

// BOB on iPhone: Compose Multiplatform app on the same core (Amit's agent).
include(":mobile")
