# Julieta's tasks: the Agent Board tool window

**For Julieta's AI coding agent. Read this whole file, then `docs/protocol.md`, before you edit anything.**

## Your role

- You are **Agent C**, agent id **`julieta-claude`**.
- You own the **`idea-plugin`** module only, on branch **`agent/idea-plugin`**.
- Goal: an IntelliJ IDEA tool window called **Agent Board** that shows the shared board: agents, tasks, conflicts, messages and handoffs.

## Rules

- Edit only files under `idea-plugin/`. Do not edit `core/` or `mcp/`. If you need a change there, ask on the coordination Issue.
- Never commit tokens or secrets.
- Commit and push after every task below. Keep each commit building.
- Julieta does **not** need IntelliJ installed. Gradle downloads IntelliJ IDEA itself: `buildPlugin` builds the plugin and `runIde` opens a test IntelliJ window with the plugin loaded. If Julieta wants to see it on her own screen, she can install the free IntelliJ IDEA, but it is optional.

## What already exists (verified to build)

| File | State |
| --- | --- |
| `idea-plugin/build.gradle.kts` | Working: IntelliJ Platform Gradle Plugin 2.20.1, targets IntelliJ IDEA 2026.2, depends on `:core`, passes `AGENT_BOARD_*` and `GITHUB_TOKEN` env vars to `runIde` |
| `idea-plugin/src/main/resources/META-INF/plugin.xml` | Registers the `Agent Board` tool window on the right |
| `idea-plugin/src/main/kotlin/com/agentboard/plugin/AgentBoardToolWindowFactory.kt` | Starter: shows one label. **You replace this UI.** |
| `core` | Done and tested: `BoardState`, `BoardClient`, `GitHubIssueStore`, `DemoFixtures` |

Data you display comes from `com.agentboard.core.BoardState`:
- `tasks: Map<String, TaskState>`: `taskId`, `owner`, `paths`, `branch`, `status`, `lastMessage`
- `agents: Map<String, AgentState>`: `agent`, `activeTasks`, `branch`, `lastSeen`
- `openConflicts: List<Conflict>`: `claimant`, `currentOwner`, `taskId`, `overlappingPaths`, `reason`
- `timeline: List<PostedEvent>`: each has `event.type`, `event.agent`, `event.toAgent`, `event.message`, `event.summary`, `event.branch`, `event.commit`, `createdAt`

## Step 0: set up (10 min)

```bash
git clone https://github.com/agtheproducer/impact_ledger.git agent-board
cd agent-board
git checkout agent/idea-plugin
./gradlew :core:test :idea-plugin:buildPlugin     # first run downloads IntelliJ (~1–2 GB)
```

Needs JDK 21. **Done when:** both tasks pass and `idea-plugin/build/distributions/idea-plugin.zip` exists.

## Task 1: board source (fixtures first)

Create `idea-plugin/src/main/kotlin/com/agentboard/plugin/BoardSource.kt`:

- A `BoardSource` interface with `fun load(): BoardState` and a `label: String`.
- `FixtureSource(stage: DemoFixtures.Stage)`: returns `DemoFixtures.state(stage)`.
- `LiveSource`: wraps `BoardClient(GitHubIssueStore.fromEnvironment(), agent)`, where `agent` comes from env `AGENT_BOARD_AGENT` (default `julieta-claude`).
- `fun liveAvailable(): Boolean`: true when `AGENT_BOARD_REPO`, `AGENT_BOARD_ISSUE` and `GITHUB_TOKEN` are all set.

**Commit:** `idea-plugin: board sources (fixtures and live)`

## Task 2: the board panel

Create **`idea-plugin/src/main/kotlin/com/agentboard/plugin/Dashboard.kt`**. Use this exact file name: the demo story is two agents fighting over this file.

Build a `Dashboard(project: Project, parentDisposable: Disposable) : JBPanel<...>` with `BorderLayout`, using native IntelliJ components (`JBPanel`, `JBLabel`, `JBScrollPane`, `JBList` or simple card panels, `JBUI.Borders`, `JBColor`). From top to bottom:

1. **Toolbar row:** a combo box with `Start`, `Conflict`, `Handoff`, `Resolved`, `Live` (`Live` only when `liveAvailable()`), and a **Refresh** button.
2. **Conflict banner:** visible only when `openConflicts` is not empty. Warning colour (use `JBColor` so it works in light and dark themes). Text like: *"Conflict: amit-claude and julieta-claude both claimed Dashboard.kt"* plus the `reason`.
3. **Agents section:** one card per agent: name, active task(s), branch, last seen.
4. **Tasks section:** one card per task: task id, owner (or "free"), status, paths (file names), branch.
5. **Timeline section** (scrollable, newest at the bottom): one row per event: time, agent → to_agent, type icon or label, message.
   - **Handoff rows are cards:** summary, branch, commit, and a clickable link `https://github.com/agtheproducer/impact_ledger/commit/<commit>` opened with `BrowserUtil.browse(...)`.

Rendering: one function `render(state: BoardState)` that rebuilds the sections. Keep the UI readable at the default tool window width (about 350–450 px).

**Commit:** `idea-plugin: Agent Board panel with fixtures`

## Task 3: wire it into the tool window

Replace the label in `AgentBoardToolWindowFactory` with `Dashboard(project, toolWindow.disposable)`.

**Done when:** `./gradlew :idea-plugin:runIde` opens IntelliJ, the **Agent Board** tool window (right side) shows the board, and switching Start → Conflict → Handoff → Resolved changes it as follows:

| Stage | What judges must see |
| --- | --- |
| Start | julieta-claude owns `dashboard`, codex owns `core-tests`, no conflict |
| Conflict | Red/orange banner: amit-claude's claim on `Dashboard.kt` clashes with julieta-claude |
| Handoff | Handoff card: julieta-claude → amit-claude, commit `a91f2c7`, banner gone |
| Resolved | amit-claude owns `dashboard`, julieta-claude moved to `plugin-tests` |

Take a screenshot of each stage for the pitch (save under `docs/screenshots/`).

**Commit:** `idea-plugin: tool window shows the four demo stages` and push.

## Task 4: live mode and auto-refresh

- In `Live` mode, load the board on a background thread (`ApplicationManager.getApplication().executeOnPooledThread { ... }`) and update the UI with `ApplicationManager.getApplication().invokeLater { render(state) }`. Never call GitHub on the UI thread.
- Refresh every 5 seconds with a timer (`javax.swing.Timer` or `com.intellij.util.Alarm`), stopped when `parentDisposable` is disposed.
- If loading fails (no network, bad token), show a small error line in the panel instead of crashing.
- Show "last updated HH:mm:ss" in the toolbar.

To test live mode, set the env vars before `runIde`:
```bash
export AGENT_BOARD_REPO=agtheproducer/impact_ledger
export AGENT_BOARD_ISSUE=<issue number from docs/protocol.md>
export GITHUB_TOKEN=<Julieta's own token>
export AGENT_BOARD_AGENT=julieta-claude
./gradlew :idea-plugin:runIde
```

**Done when (gate):** a comment posted by Amit's agent appears in the tool window within about 5 seconds.

**Commit:** `idea-plugin: live board with auto-refresh`

## Task 5 (only if time): polish

- Highlight the current agent's own cards (`AGENT_BOARD_AGENT`).
- Clicking a path in a task card opens that file in the editor if it exists in the project.
- A small "How it works" line at the bottom: *Claims → conflict → handoff with a real commit.*

## After every task

Post a short comment on the coordination Issue: branch, commit id, what changed, how to run it, questions for Amit's agent.

## Do not

- Do not change the event format, `core`, or `mcp`.
- Do not add a database or server: the GitHub Issue is the shared store.
- Do not spend time on icons or animations before Task 4 works.
