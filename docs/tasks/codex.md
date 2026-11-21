# Codex's tasks (Julieta's second agent): core extras, demo tools, fallback

**For Julieta's Codex agent. Read this whole file, then `docs/protocol.md`, before you edit anything.**

## Your role

- You are **Agent D**, agent id **`codex`**.
- You own the **`core`** module and a new **`cli`** module, on branch **`agent/core`**.
- Julieta's Claude works on `idea-plugin` on the same laptop. **Never work in the same folder as Claude.** Use your own worktree (Step 0).
- Your job makes the demo safe: better conflict detection, a command-line tool to test and replay the board, and a backup window if the IntelliJ plugin is late.

## Rules

- Edit only `core/`, the new `cli/`, and the `include(":cli")` line in `settings.gradle.kts`.
- **Keep the event format and the public API of `BoardClient` unchanged.** `mcp` and `idea-plugin` depend on them. Ask on the coordination Issue before changing them.
- All existing tests must keep passing. Never commit tokens. Commit and push after each task.

## Step 0: your own worktree (on Julieta's laptop)

From Julieta's clone of the repo:

```bash
git fetch origin
git worktree add ../agent-board-codex agent/core
cd ../agent-board-codex
./gradlew :core:test
```

Work only in `../agent-board-codex`. **Done when:** 8 tests pass.

## Task 1: folder overlap in conflict detection

Today only exact paths clash. Make a claim on a folder clash with files inside it, both ways:

- `idea-plugin/src/` overlaps `idea-plugin/src/main/kotlin/Dashboard.kt`
- `Dashboard.kt` claimed after `idea-plugin/` was claimed is a conflict too
- A path ending in `/` is a folder. Compare normalised paths (no leading `./`, forward slashes)
- `idea-plugin/src` must **not** overlap `idea-plugin/src2/File.kt`

Change it in one place (a helper used by `BoardReducer.claim` and `BoardState.ownerOfPath`), and add tests for each case above to `BoardReducerTest`.

**Commit:** `core: folder overlap in conflict detection`

## Task 2: `cli` module for testing and the demo

Create `cli/build.gradle.kts` (Kotlin JVM + `application`, depends on `project(":core")`, `jvmToolchain(21)`, main class `com.agentboard.cli.MainKt`) and add `include(":cli")` to `settings.gradle.kts`. It uses `GitHubIssueStore.fromEnvironment()` and `AGENT_BOARD_AGENT`.

Commands (`./gradlew :cli:run --args="..."`):

| Command | Does |
| --- | --- |
| `board` | Prints the current board: tasks with owners, open conflicts, last 10 events |
| `claim <task_id> <branch> <path>...` | Claims and prints owned or the conflict |
| `say <message> [--to agent]` | Posts a message |
| `handoff <task_id> <to_agent> <branch> <commit> <summary>` | Posts a handoff |
| `release <task_id>` | Releases a task |
| `replay-demo [--delay 3]` | Posts the 8 `DemoFixtures.events` in order, one every N seconds, each with its own agent id. **This is the demo safety net:** if live agents misbehave on stage, replay the story into the real Issue and the tool window shows it live. |

Print short readable text, not stack traces: on errors show one line explaining what to set or fix.

**Done when:** `board` and `say` work against the live Issue, and `replay-demo` makes the 4 stages appear on the board. **Commit:** `cli: board commands and demo replay`

## Task 3 (only if Amit or Julieta ask, around hour 4): fallback window

If the IntelliJ plugin is blocked, add a `window` command to `cli` that opens a simple Swing window with the same content as the plugin: conflict banner, tasks, agents and timeline from `BoardClient.board()`, refreshed every 5 seconds. Keep it plain; it only has to work.

**Commit:** `cli: fallback board window`

## Task 4: help integration

When `agent/mcp` and `agent/idea-plugin` are merged into `demo`, run `./gradlew build` and report failures on the coordination Issue with the exact error. Fix only things inside `core` or `cli`.

## After every task

Post a short comment on the coordination Issue: branch, commit id, what changed, how to run it.
