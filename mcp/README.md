# mcp — owner: Agent B (branch `agent/mcp`)

Local **stdio MCP server** in Kotlin that exposes the board to coding agents.

## To build

1. Create `mcp/build.gradle.kts` (Kotlin JVM + `application`), depend on `project(":core")` and the Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`).
2. Add `include(":mcp")` in `settings.gradle.kts`.
3. Implement the six tools in `docs/protocol.md`, each a thin wrapper around `BoardClient`.
4. Start against `InMemoryBoardStore(DemoFixtures.comments())`, then switch to `GitHubIssueStore.fromEnvironment()`.
5. Document how to connect it to the JetBrains AI agent (or Claude Code) on each laptop.

**Gate:** two real agent sessions read a claim and exchange a message.

Do not edit `core` or `idea-plugin`. Ask on the coordination Issue for contract changes. Never commit tokens.
