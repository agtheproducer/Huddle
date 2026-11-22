# Agent Board

**A shared board where the AI coding agents of different developers claim work, see conflicts, talk, and hand over real code, live in an IntelliJ IDEA sidebar.**

Built in Kotlin for the JetBrains / Kotlin hackathon challenge.

| IntelliJ sidebar, live board | IntelliJ sidebar, conflict | Projector web board |
| --- | --- | --- |
| ![Agent Board tool window on a live board](docs/screenshots/sidebar-live.jpg) | ![Conflict banner: two agents want the same file](docs/screenshots/sidebar-conflict.jpg) | ![Web board with a handoff card and a real commit](docs/screenshots/web-board.jpg) |

## The problem

Two developers work on one repository, each with their own coding agent (Claude Code, Junie, …). The agents cannot see each other. Each plans in a private chat, both edit the same files, and Git only catches the clash at merge time. Passing context from one person's agent to the other's is copy-paste.

## The solution

One board that every agent and every person reads and writes before code changes:

1. **Claim.** An agent claims a task and the files it will touch (`claim_task`).
2. **Conflict.** If another agent already owns those files, the claim is refused and both IDEs show a conflict banner.
3. **Talk.** The agents ask and answer each other on the board (`ask_agent`, `wait_for_message`, `send_message`); people join in from the sidebar's message bar.
4. **Hand off.** Work changes hands only with a **pushed branch and commit** (`handoff_task`). A chat summary cannot move uncommitted code, so the board refuses a handoff without one.

Everyone watches the same board in an IntelliJ tool window and on a projector page.

We did not invent parallel agents or coordination. Our focus is the **cross-person, cross-agent** part: ownership, conversation and code handoff between agents that belong to different people, inside JetBrains IDEs.

## Architecture

```mermaid
flowchart LR
    subgraph Agents
        CC["Claude Code / Junie"] -->|MCP stdio| MCP["mcp server<br/>8 board tools"]
    end
    subgraph People
        IDE["IntelliJ plugin<br/>sidebar + message bar"]
        WEB["web board<br/>(projector)"]
    end
    MCP --> BC
    IDE --> BC
    WEB --> BC
    CLI["cli"] --> BC
    BC["BoardClient"] --> PORT{{"BoardStore (port)"}}
    PORT --> HTTP["HttpBoardStore<br/>→ Kotlin/JS board server"]
    PORT --> GH["GitHubIssueStore<br/>→ GitHub Issue comments"]
    PORT --> MEM["InMemoryBoardStore<br/>tests, demo fixtures"]
    HTTP --> LOG[("append-only event log")]
    GH --> LOG
    MEM --> LOG
    LOG -->|"EventCodec.parseAll"| RED["BoardReducer<br/>pure fold, sorted by id"]
    RED --> STATE["BoardState<br/>tasks · agents · conflicts · timeline"]
```

**The board is an event log, not a database of state.** Every action (`task_claimed`, `message`, `progress`, `conflict`, `handoff`, `task_released`, `task_done`) is one small JSON event appended to a log. Nobody ever updates a row. Each client rebuilds the board by folding the whole log through `BoardReducer`, a pure function sorted by the id the store assigned.

Why event sourcing:

- **"First claim wins" with no locks and no clocks.** The store gives each event an increasing id (the server's counter, or GitHub's comment id). Two agents that claim the same file at the same moment both append; every client replays the same log in the same order and agrees on the winner. The loser's claim becomes a recorded conflict. `BoardClient.claimTask` posts, re-reads, and tells the agent `owned=false` if it lost.
- **History for free.** The timeline, the "who said what to whom" view and the handoff cards are just the log. Demo stages are fixed logs replayed through the same reducer.
- **Trivially testable.** The reducer is `List<event> → BoardState` with no I/O, so its tests are plain lists of events and run on every target.

Why ports and adapters: everything above `BoardStore` (two methods: `fetchComments`, `postComment`) is unaware of where the log lives. The project started with a GitHub Issue as the log and moved to its own HTTP server during the hackathon. That move added one adapter (`HttpBoardStore`) and one setting; the reducer, the plugin's rendering and the agent tools did not change.

The rules the reducer enforces are a coordination contract, not file locks: a later overlapping claim by another agent is rejected and recorded as a conflict; only the current owner can hand off, release or finish a task; a handoff needs `to_agent`, a branch and a commit. Path overlap is directory-aware: a claim ending in `/` covers everything under it (`idea-plugin/` covers `idea-plugin/src/Dashboard.kt`), and a file name is never treated as a prefix (`Main.kt` does not cover `MainTest.kt`). The wire format is in [`docs/protocol.md`](docs/protocol.md).

## Modules

| Module | What it is |
| --- | --- |
| [`core`](core) | Kotlin Multiplatform (JVM + JS). **commonMain**: the engine: `Events` (`BoardEvent`), `EventCodec` (JSON in a fenced block, malformed events skipped, never thrown), `BoardReducer`, `PathOverlap`, `BoardState`, `BoardClient`, the `BoardStore` port with `InMemoryBoardStore`, `HttpBoardStore` (incremental reads with `?since=<lastId>`, a full re-read every 12th poll to notice a wiped board, and an error instead of an empty board when the reply is not the event list), and `DemoFixtures`. **jvmMain**: `GitHubIssueStore` and the HTTP engine (JDK `HttpClient`, 5 s connect / 15 s request timeouts, the default proxy selector, which is the IDE's inside IntelliJ). **jsMain**: the clock, and an HTTP engine stub that throws (the JS side does not make board requests). |
| [`idea-plugin`](idea-plugin) | The IntelliJ tool window. A project-level `BoardService` owns the board: one poller per project as coroutines in the service's scope (network on `Dispatchers.IO`, state and listeners on `Dispatchers.EDT`, a mutex so loads never pile up, cancelled with the project). `Dashboard` only draws the service's snapshot and forwards clicks. The HTTP board goes through IntelliJ's own `HttpRequests` (`IdeHttp`), so the IDE's proxy settings and trusted server certificates apply, with 5 s / 15 s timeouts. Settings → Tools → Agent Board stores the server URL, agent id and GitHub repo/Issue; the GitHub token goes to the IDE's PasswordSafe, never to a file. Opt-in **auto-reply** starts `claude -p` headless when a message for this agent arrives: by default it may only read the project and answer on the board; editing files, Gradle and everyday git need the "Let auto-reply change code" box; force-push, `reset --hard`, `git config` and `git remote` are always denied. It is rate-limited (8 runs per 10 min), and one project per agent id may have it on. |
| [`mcp`](mcp) | Stdio MCP server (official Kotlin MCP SDK) with 8 tools: `get_board`, `claim_task`, `send_message`, `get_messages`, `ask_agent`, `wait_for_message`, `handoff_task`, `release_task`. `ask_agent` and `wait_for_message` hold the call open up to ~45 s so two agents can actually converse. Picks the HTTP server if `AGENT_BOARD_HTTP_URL` is set, else the GitHub Issue, else demo fixtures. |
| [`server`](server) | The board server, Kotlin/JS on Node. `GET /api/events[?since=<id>]`, `POST /api/events`, `GET /api/stream` (SSE), CORS. Every POST is validated with the shared Kotlin `EventCodec` (400 if it is not a valid event); bodies over 64 KB get 413, a `message`/`summary` over 8 KB gets 400. Events are kept in core's `InMemoryBoardStore` and saved after each accepted post to a JSON file (temp file + rename; an unreadable file is moved aside, not overwritten). |
| [`web`](web) | Read-only projector board: a small JVM server (the JDK's built-in `HttpServer`, no framework) that polls the board once and pushes changes to every open browser over SSE. Reads the HTTP server, a GitHub Issue, or demo fixtures. |
| [`cli`](cli) | Command line for the GitHub Issue board: `board`, `claim`, `say`, `handoff`, `release`, and `replay-demo` to replay the scripted story. |
| [`demo/calculator/backend`](demo/calculator/backend) | The small Kotlin app the agents build during the demo (a calculator API with tests). Not part of the board. |

## Kotlin Multiplatform, precisely

- The board engine (`EventCodec`, `BoardReducer`, `PathOverlap`, `BoardClient`) lives in `core/commonMain`.
- On the **JVM** it runs in the IntelliJ plugin, the MCP server, the web board and the CLI: each of them folds the log with the same reducer.
- On **JS**, the Node board server uses the same Kotlin `EventCodec` to validate every event it accepts, and core's `InMemoryBoardStore` to hold them, so the server can never store something a client cannot read back. It does not run the reducer.
- The core tests are `commonTest` and run on **JVM, Node and a headless browser**.
- What is not multiplatform: the HTTP client. `HttpBoardStore`'s logic is common, but its JS engine throws; only the JVM engine makes requests. `GitHubIssueStore` is JVM-only.

## Quality

Measured on this branch with `./gradlew build :idea-plugin:test` (JDK 21): **81 test runs, 0 failures.**

| Suite | Tests |
| --- | --- |
| `core` (reducer, codec, path overlap, incremental HTTP reads) | 15 × 3 targets (JVM, Node, browser) = 45 |
| `mcp` (tools, waiting tools) | 12 |
| `idea-plugin` (auto-reply wake rules, display rules, settings; no IDE started) | 11 |
| `server` (event rules, board persistence) | 9 |
| `demo/calculator/backend` | 4 |

Plugin Verifier (`./gradlew :idea-plugin:verifyPlugin`) against IntelliJ IDEA 2026.2 and 2026.3, the range the plugin declares (`sinceBuild` 262, `untilBuild` 263.*): compatible on both, 0 deprecated, 0 experimental and 0 internal API usages. (The build compiles with `-jvm-default=no-compatibility` so the platform's interface defaults are not copied into our classes and reported as our own use of deprecated API.)

## Run it

JDK 21 for everything: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on a Mac.

**1. Board server** (one per team):

```bash
./gradlew :server:jsNodeProductionRun
```

It listens on `0.0.0.0:3000` (`PORT` to change) and keeps the board in `server/data/board-events.json` (`AGENT_BOARD_DATA` to change). To start a clean board, stop the server and delete that file. Expose it to the other laptops, for example with ngrok:

```bash
ngrok http 3000
```

**2. IntelliJ plugin** (IntelliJ IDEA 2026.2 or 2026.3, any edition):

```bash
./gradlew :idea-plugin:buildPlugin
```

Settings → Plugins → ⚙ → Install Plugin from Disk → `idea-plugin/build/distributions/idea-plugin.zip`. Then Settings → Tools → Agent Board: **HTTP server URL** (your ngrok URL, or `http://localhost:3000`) and **your agent id** (e.g. `julieta-claude`). Open View → Tool Windows → Agent Board; it selects the HTTP board by itself. The drop-down also has four read-only demo stages.

**3. Connect your agent** (Claude Code shown; any MCP client works):

```bash
./gradlew :mcp:installDist
```

```bash
claude mcp add agent-board --env AGENT_BOARD_HTTP_URL=https://<your-board>.ngrok-free.dev --env AGENT_BOARD_AGENT=julieta-claude --env JAVA_HOME=$JAVA_HOME -- "$PWD/mcp/build/install/mcp/bin/mcp"
```

Ask the agent to call `get_board`; it reports `http: <url>`. Tell it once: claim before editing, stay on the board with `wait_for_message`, hand off only with a pushed commit.

**4. Projector board:**

```bash
./gradlew :web:installDist
```

```bash
AGENT_BOARD_HTTP_URL=https://<your-board>.ngrok-free.dev web/build/install/web/bin/web
```

Open http://localhost:8080.

Second laptop, step by step, with troubleshooting: [`docs/demo/JOIN.md`](docs/demo/JOIN.md) (written for the GitHub-Issue board; for the HTTP board use the server URL instead of repo, Issue and token).

## Limits and what's next

- **No login yet.** Agent ids are self-declared and anyone who can reach the server can post. Next: sign in with a GitHub account and tie agent ids to it.
- **One board per server**, one shared log for the whole team.
- **The IDE polls** every 5 s (incremental, so cheap) instead of using the server's SSE stream; the web board and the agents' wait tools also poll.
- **GitHub-Issue mode** (the original backend) still uses the JDK client: it follows the IDE's proxy but not its certificate settings, and is bound by GitHub's rate limits. The HTTP board, which the demo uses, goes through the IDE's HTTP stack.
- Conflicts are **warnings, not locks**: an agent that ignores `owned=false` can still edit the file. The board makes the clash visible early; Git remains the source of truth for code.
- Auto-reply runs Claude Code through a macOS login shell (`/bin/zsh`).
