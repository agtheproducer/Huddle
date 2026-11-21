# Team plan: two humans, three agents

**Goal for the demo:** two laptops, real agents, one board. A duplicate claim shows a conflict, a handoff carries a real pushed commit, and the Agent Board tool window in IntelliJ shows it all.

## Already done (on `main`)

- Repo restructured for Agent Board. The old Impact Ledger app is safe on branch `archive/impact-ledger`.
- `core` module **built and tested** (8 tests pass): event format, GitHub Issue store, board state, first-claim-wins rule, conflicts, handoffs, and demo fixtures for the four demo stages.
- `docs/protocol.md`: the contract everyone codes against.
- Branches `agent/core`, `agent/mcp`, `agent/idea-plugin` created from `main`.

## Who does what

| Person | Agent | Module / branch | Job |
| --- | --- | --- | --- |
| **Amit** | Claude (Amit's account) | `mcp` on `agent/mcp` | MCP server with the 6 board tools; connect it to the agents on both laptops. Amit is also **integration owner and pitch lead**. |
| **[Teammate]** | Claude (teammate's account) | `idea-plugin` on `agent/idea-plugin` | IntelliJ tool window: agent cards, task cards, conflict banner, timeline, handoff card. Hardest part: start first. |
| Either | Codex | `core` on `agent/core` | Extras only: folder/prefix path matching, more tests, and the fallback desktop board if the plugin is blocked. |

One folder, one agent: never let two agents edit the same working directory. Use a separate clone or `git worktree` per agent.

## Timeline (adjust to the submission time: [SUBMISSION_TIME])

| When | Amit | [Teammate] | Gate |
| --- | --- | --- | --- |
| **0:00–0:30** | Create the coordination Issue, put its number in `docs/protocol.md`; both create a GitHub token (Issues read/write) and set env vars | Clone, open in IntelliJ, check `./gradlew :core:test` passes | Both laptops post and read a test comment on the Issue |
| **0:30–3:00** | Agent B builds `mcp` against `DemoFixtures` | Agent C builds the tool window against `DemoFixtures` | Each module runs alone on fixtures |
| **3:00–5:00** | Switch `mcp` to the live Issue; connect a real agent on each laptop | Switch the tool window to the live Issue, auto-refresh | A message from the other laptop appears in the tool window |
| **5:00–6:30** | Merge `agent/mcp` then `agent/idea-plugin` into a `demo` branch; build; run the full story | Fix UI issues found in integration | Conflict → handoff → reassignment works across two laptops |
| **6:30–end** | Rehearse the pitch, record a backup video | Prepare the architecture slide and code tour | Feature freeze at least 1 hour before submission |

**Fallback:** if the IntelliJ plugin setup blocks you by hour 4, Codex builds a small Kotlin desktop window on `core` showing the same board. The cross-agent handoff over MCP is the core claim; keep that working first.

## Setup on each laptop

```bash
git clone https://github.com/agtheproducer/impact_ledger.git agent-board
cd agent-board
./gradlew :core:test           # should pass

# never commit these
export AGENT_BOARD_REPO=agtheproducer/impact_ledger
export AGENT_BOARD_ISSUE=<issue number>
export GITHUB_TOKEN=<your own token>
export AGENT_BOARD_AGENT=amit-claude   # or <teammate>-claude, codex
```

For each extra agent on the same laptop: `git worktree add ../agent-board-codex agent/core`.

## Copy-paste prompts

**Amit's Claude (Agent B, mcp):**
> You are Agent B on Agent Board. Read `docs/PROJECT_BRIEF.md` and `docs/protocol.md` fully. Work only in the `mcp` module on branch `agent/mcp`. Build a local Kotlin stdio MCP server using the Kotlin MCP SDK, exposing `get_board`, `claim_task`, `send_message`, `get_messages`, `handoff_task`, `release_task` as thin wrappers around `BoardClient` in `core`. Start with `InMemoryBoardStore(DemoFixtures.comments())`, then `GitHubIssueStore.fromEnvironment()`. Add `include(":mcp")` to `settings.gradle.kts`. Return short JSON. Document how to connect the server to the JetBrains AI agent and to Claude Code. Do not edit `core` or `idea-plugin`; ask on the coordination Issue for contract changes. Commit and push small working steps. Never commit tokens.

**[Teammate]'s Claude (Agent C, idea-plugin):**
> You are Agent C on Agent Board. Read `docs/PROJECT_BRIEF.md` and `docs/protocol.md` fully. Work only in the `idea-plugin` module on branch `agent/idea-plugin`. Create an IntelliJ IDEA plugin in Kotlin (IntelliJ Platform Gradle Plugin, official template) depending on `project(":core")`, and add `include(":idea-plugin")` to `settings.gradle.kts`. Build an **Agent Board** tool window showing agent cards, task cards, a conflict banner, a message timeline and a handoff card with the commit. First render `DemoFixtures.state(...)` for each of the four stages, then read live data with `BoardClient(GitHubIssueStore.fromEnvironment(), agent)` and refresh every few seconds. Prefer native IntelliJ UI components. Do not edit `core` or `mcp`. Commit and push small working steps; after each milestone, comment on the coordination Issue with branch, commit, changed files and how to run it.

**Codex (core extras / fallback):**
> You are working on Agent Board, branch `agent/core`, module `core` only. Read `docs/protocol.md`. Keep all existing tests passing. Add folder/prefix path overlap (a claim on `ui/` conflicts with `ui/Dashboard.kt`) behind the existing rules, with tests. If asked, build a fallback Kotlin desktop window that shows `BoardClient.board()`. Do not change the event format without asking on the coordination Issue.

## Questions to settle now

1. Submission time and judging format?
2. Which laptop goes on the projector?
3. Does each laptop have IntelliJ IDEA with an AI agent that supports MCP servers, and enough credits?
4. What exactly earns the JetBrains prize? Ask JetBrains staff.
5. Rename the repo to `agent-board` on GitHub? (Settings → Rename; old links redirect.)
