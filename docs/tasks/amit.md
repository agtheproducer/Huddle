# Amit's tasks: setup, the MCP server, integration and pitch

## Part 1: for Amit himself (do first, ~20 min)

- [x] **Coordination Issue created:** [#1](https://github.com/agtheproducer/Huddle/issues/1). A duplicate (#2) was opened a minute later; close it as a duplicate of #1.
- [x] Issue number set in `docs/protocol.md` and pushed to `main`.
- [ ] **Create a GitHub token** for yourself (GitHub → Settings → Developer settings → Fine-grained tokens) with access to this repo: Issues read/write, Contents read/write. Ask Julieta to make her own. Never paste tokens in the repo or the Issue.
- [ ] Post a test comment on the Issue from each laptop's token (the gate for Phase 0).
- [ ] Send Julieta: the repo link, the Issue number, and `docs/tasks/julieta.md`.
- [ ] Ask JetBrains staff exactly what earns their prize, and note it in `docs/TEAM_PLAN.md`.

## Part 2: for Amit's AI coding agent

**Read this whole section, then `docs/protocol.md`, before you edit anything.**

### Your role

- You are **Agent B**, agent id **`amit-claude`**.
- You own the **`mcp`** module only, on branch **`agent/mcp`**.
- Goal: a local stdio MCP server so coding agents can use the board.

### Rules

- Edit only `mcp/`. Ask on the coordination Issue for changes to `core` or `idea-plugin`.
- **stdout carries the MCP protocol.** Never print to stdout; log to stderr only. The build already disables the kotlin-logging startup banner.
- Never commit tokens. Commit and push after each task.

### What already exists (verified)

`mcp/build.gradle.kts` (Kotlin MCP SDK 0.16.0, `application` plugin, depends on `:core`) and a starter `mcp/src/main/kotlin/com/agentboard/mcp/Main.kt` with one tool, verified to start and answer `initialize` and `tools/list` over stdio. Key SDK calls, already in the starter:

- `Server(Implementation(...), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))))`
- `server.addTool(name, description, inputSchema = ToolSchema(properties = buildJsonObject { ... }, required = listOf(...))) { request -> CallToolResult(content = listOf(TextContent(json))) }`
- Arguments: `request.arguments?.get("task_id")?.jsonPrimitive?.content`
- Transport: `server.createSession(StdioServerTransport(System.in.asSource().buffered(), System.out.asSink().buffered()))`

### Step 0: set up

```bash
git clone https://github.com/agtheproducer/Huddle.git agent-board
cd agent-board && git checkout agent/mcp
./gradlew :core:test :mcp:installDist
```

### Task 1: the six tools on fixtures

Rewrite `Main.kt` (split into `Tools.kt` if it gets long):

- Build one `BoardClient`: if `AGENT_BOARD_REPO`, `AGENT_BOARD_ISSUE` and `GITHUB_TOKEN` are set use `GitHubIssueStore.fromEnvironment()`, otherwise `InMemoryBoardStore(DemoFixtures.comments())`. Agent id from `AGENT_BOARD_AGENT` (default `amit-claude`).
- Tools, each returning short JSON text:

| Tool | Arguments | Returns |
| --- | --- | --- |
| `get_board` | none | active tasks (id, owner, paths, branch), open conflicts, last 10 timeline events |
| `claim_task` | `task_id`, `paths` (array), `branch`, `message`? | `{owned, owner, conflict?}` from `claimTask` |
| `send_message` | `message`, `to_agent`?, `task_id`? | the posted comment id |
| `get_messages` | `since_id`? | messages/handoffs/conflicts for this agent from `messages(...)` |
| `handoff_task` | `task_id`, `to_agent`, `summary`, `branch`, `commit` | the posted comment id; reject if commit or branch missing |
| `release_task` | `task_id`, `message`? | the posted comment id |

- Tool descriptions must tell the agent **when** to use them, e.g. `claim_task`: "Call before editing files. If owned=false, do not edit; talk to the owner with send_message."
- Wrap errors: return `{"error": "..."}`, never crash the server.

**Done when:** `./gradlew :mcp:installDist` builds and a `tools/list` request lists all six tools. **Commit:** `mcp: six board tools`

### Task 2: connect real agents

- Write `mcp/README.md` "Connect" section with the exact config for:
  - **JetBrains AI Assistant / Junie:** Settings → Tools → AI Assistant → Model Context Protocol (MCP) → add a server, command `<repo>/mcp/build/install/mcp/bin/mcp`, env `AGENT_BOARD_REPO`, `AGENT_BOARD_ISSUE`, `GITHUB_TOKEN`, `AGENT_BOARD_AGENT`. For agents in the AI chat, enable "Pass custom MCP servers" (Settings → Tools → AI Assistant → Agents).
  - **Claude Code:** `claude mcp add agent-board -- <repo>/mcp/build/install/mcp/bin/mcp` with the same env vars.
- Add a project rule file the agents follow (`AGENTS.md` is already in the repo root: propose wording changes on the Issue).

**Gate:** two real agent sessions (Amit's laptop and Julieta's) read the same claim and exchange a message. **Commit:** `mcp: connection guide`

### Task 3: integration (Amit's agent, with Amit)

1. Create branch `demo` from `main`; merge `agent/mcp`, then `agent/idea-plugin`.
2. `./gradlew :core:test :mcp:installDist :idea-plugin:buildPlugin` must pass.
3. Run the full story live across two laptops (see `docs/TEAM_PLAN.md`, demo script).
4. Fix only integration bugs. Feature freeze 1 hour before submission.

## Part 3: pitch (Amit)

- 3-minute script: problem (agents can't see each other) → live demo (conflict → handoff with real commit → reassignment) → architecture (GitHub Issue as event log, MCP server, IntelliJ tool window, all Kotlin) → what's real vs. staged.
- Record a backup video of the working demo.
- Be ready for: "How is this different from Vibsync, Shepherd or agent-comm?" Answer: cross-person, cross-account handoff with real commits, visible inside IntelliJ.
