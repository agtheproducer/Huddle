# Agent Board — LauzHack project brief

**Share this entire document with the other teammate and their Claude coding agent.**

Status: in build. The linked interface is a clickable design prototype, not a working IntelliJ plugin. GitHub setup is done: the repo is `agtheproducer/Huddle` and the coordination Issue is #1.

**Interface concept:** [Agent Board in IntelliJ IDEA](https://chatgpt.com/library?file_id=libfile_fde3_f-6fedb1b8a51481918ed9d7337725533a_04ca7aa2bdb081918017ad45533b7eb2&account_id=personal). Click the four steps along the bottom: Start → Conflict → Handoff → Resolved.

## 1. The idea in plain language

Two developers work on the same software project, each with a coding AI. A third AI can also contribute. Today those AIs can make code in parallel, but the developers still have to explain what each AI is doing, stop overlapping edits, and pass code and decisions between them.

**Agent Board gives the agents a shared project board inside IntelliJ IDEA.** Before coding, an agent claims a task and lists the files it expects to change. Other agents see that claim, can ask questions, exchange API contracts, and hand over work with a Git commit and a short summary. When two agents claim the same task or file, the board shows a conflict and asks them to agree who continues.

This is coordination for **different developers' agents on different accounts**, potentially using different providers: two Claude accounts and one OpenAI/Codex account. Running multiple agents or branches by itself already exists; our focus is the **cross-person, cross-agent conversation, task ownership, and code handoff**. We should not claim that we invented parallel agents or that no competing coordination product exists.

### Example judges should see

1. Julieta's Claude: “I'm taking the dashboard, editing `Dashboard.kt`.”
2. Teammate's Claude: “I started that file too. What have you built?”
3. Board: **Conflict: two agents requested `Dashboard.kt`.**
4. Julieta's Claude: “The cards and state model are committed as `a91f2c7` on `agent/dashboard`. Charts need wiring. You can take over.”
5. Teammate's Claude reads the summary and commit, accepts dashboard ownership; Julieta's Claude moves to tests.
6. Codex sees the current board and takes a separate task.

Agents do not have awareness of each other's private chats or uncommitted files. They know what other agents explicitly publish to the shared board. Active agents can poll/check it and respond; an idle agent does not automatically wake just because a message arrives. A fully autonomous wake-up orchestrator is outside the minimum demo.

## 2. Users, value, and interface

**Users:** software teams already using IntelliJ IDEA with Claude Code, JetBrains Air, Codex, or other MCP-compatible agents.

**Problem:** task plans, file ownership, and implementation decisions are siloed inside each developer's local agent conversation. Git catches conflicts late; passing context between people is manual.

**Product:** a Kotlin IntelliJ IDEA tool window titled **Agent Board**, plus a Kotlin MCP server exposing the same board to agents.

The tool window should show:

- Agent cards: identity, active task, branch, status, last seen.
- Task cards: owner, claimed paths, branch, status.
- Conversation timeline: messages and handoffs from agents, with commit links.
- Conflict banner: overlapping claim with a clear explanation.
- Four demo states: start, collision, handoff, resolved.

The mockup shows visual direction; use native IntelliJ UI controls if feasible. The mockup code is not the production plugin.

## 3. System design

```text
Laptop A / IntelliJ + Claude ─┐
                              ├─ local Kotlin MCP bridge ─ GitHub Issue comments
Laptop B / IntelliJ + Claude ─┤                           (shared event log)
                              │
Codex on a separate worktree ─┘

IntelliJ Agent Board tool window ─ reads the same Issue comments
Each agent's Git branch ─ holds its actual code and commits
```

**GitHub is both the code host and the prototype's shared data store.** Use one coordination Issue in `agtheproducer/Huddle`; append structured comments to it for claims, messages, releases, and handoffs. The Kotlin code reads comments in the stable order returned by GitHub, turns them into current board state, and refreshes the UI every few seconds. This avoids deploying a separate database or service for a one-day prototype.

**Important distinction:** Git branches hold code. Issue comments hold coordination events. A chat summary cannot transfer actual uncommitted code: a handoff must include a pushed branch and commit ID.

### Proposed modules

| Module | Responsibility | Owner |
| --- | --- | --- |
| `core` | Event types, GitHub API adapter, state reconstruction, claim/conflict rules | Agent A, Claude |
| `mcp` | Local stdio MCP server with board tools, using `core` | Agent B, Claude |
| `idea-plugin` | IntelliJ IDEA tool window and refresh, using `core` | Agent C, Codex |
| `docs` | Contract, setup, demo, judges' technical explanation | Agent A initially; integration owner at end |

The Kotlin MCP SDK is a suitable base for the local server. IntelliJ Platform plugin APIs supply the tool window. JetBrains Air can pass a locally configured MCP server to compatible agents. A remotely hosted cloud coding session may not see local stdio servers or your laptop's credentials: cloud agents should **write their assigned branch through GitHub**, while the final two-laptop demo runs compatible agents locally in JetBrains. Verify each account's actual client and entitlements during setup; a subscription to a chat app does not imply paid API access.

### Minimal event contract — agree before parallel coding

Each new GitHub Issue comment contains a JSON object (possibly inside a fenced `json` block):

```json
{
  "v": 1,
  "type": "task_claimed",
  "agent": "julieta-claude",
  "task_id": "dashboard",
  "branch": "agent/dashboard",
  "paths": ["idea-plugin/src/main/kotlin/.../Dashboard.kt"],
  "message": "Building the dashboard"
}
```

Supported types for MVP: `task_claimed`, `message`, `progress`, `conflict`, `handoff`, `task_released`, `task_done`. For a handoff include `to_agent`, `summary`, `branch`, `commit`, and `paths`. GitHub's comment ID and creation time supply event ordering; do not rely on the agent's clock. Ignore malformed comments safely. Store only concise project context and links, never secrets or entire private chat histories.

**Claim rule for demo:** first valid claim in Issue-comment order owns a task/path. A later overlapping claim is rejected by the state reducer and shown as a conflict. This is a coordination warning, not an OS-level file lock; agents work in different branches/worktrees. The agent must check the board immediately before editing a newly claimed path. Re-read the event log after posting a claim so concurrent claims are resolved deterministically. Exact path overlap is sufficient for MVP; folder/prefix matching is a bonus.

### MCP tool contract

| Tool | Purpose |
| --- | --- |
| `get_board()` | Get current owners, tasks, conflicts, and recent messages |
| `claim_task(task_id, paths, branch)` | Publish a claim, then re-read to confirm ownership or return conflict |
| `send_message(to_agent, message)` | Ask or answer a question on the shared board |
| `get_messages(agent, since_id?)` | Read new messages and handoffs |
| `handoff_task(task_id, to_agent, summary, branch, commit)` | Transfer context with real, pushed code reference |
| `release_task(task_id)` | Give up a claim |

Keep the number of tools small and return clear, short JSON. Check messages at task start, before editing a newly claimed path, after a conflict, and before finishing. The UI polls every few seconds; agents do not need an endless automatic conversation loop.

## 4. Work order and gates

**Phase 0 — access and shared contract (both humans, 20–30 min).** Ensure both laptops can clone and push to `agtheproducer/Huddle`. Create the coordination Issue and note its number. Make three branches/worktrees: `agent/core`, `agent/mcp`, `agent/idea-plugin`. Each laptop has its own GitHub access for Issue comments. Keep authentication local and out of Git. Create and push `docs/protocol.md` with the event/tool contract and module interfaces. **Gate:** all agents can read the contract; both laptops can post and read a test comment.

**Phase 1 — core engine (Agent A).** Implement issue-comment fetch/post, event parsing, state reconstruction, collision rule, and focused tests. Commit a usable interface early; Agent B and C can develop against fixtures until then. **Gate:** two competing claims produce one owner and one conflict.

**Phase 2 — MCP bridge (Agent B).** Implement the listed tools over the core. Run it locally using stdio and connect one Claude agent, then a second agent. Do not configure credentials in committed files. **Gate:** two real agent sessions can read a claim and exchange a message.

**Phase 3 — IDEA UI (Agent C).** Create an IntelliJ plugin project in Kotlin with an `Agent Board` tool window. Show fixtures first; then wire to `core` and GitHub issue data. Implement agent cards, task ownership, conflict banner, and conversation/handoff timeline. **Gate:** a message from the other laptop appears in the tool window after refresh.

**Phase 4 — integration (one human/agent, shared demo branch).** Merge core, MCP, then IDEA UI. Run Gradle build; install/run the plugin in a development IDEA instance; connect local agents. Fix integration blockers. **Gate:** the complete dashboard conflict → handoff → reassignment sequence works across two laptops.

**Phase 5 — pitch and evidence.** Record a short live flow and fallback screen capture. Be ready to show the architecture, source, claim-order test, and limitations. Verify with JetBrains staff exactly what use of their AI tooling earns the sponsor bonus; an IntelliJ plugin or IDE alone may not be enough. Use their AI tooling for a real development step and keep a short record of that workflow.

If the IntelliJ plugin setup blocks the demo, a small Kotlin desktop board reading the same `core` is a fallback. Keep the MCP interaction and real cross-agent handoff; that is the product's core claim.

## 5. Safe parallel workflow and laptop switching

- **Three accounts, two laptops:** two active agent sessions can run on separate laptops; the third can run in a separate worktree on either laptop or as a cloud coding session on its own branch. One physical directory must never have two coding agents modifying it simultaneously.
- **Branch ownership:** Agent A edits `core` and initial `docs/protocol.md`; Agent B edits `mcp`; Agent C edits `idea-plugin`. Ask through the Issue before changing another owner's contract or files.
- **Sync:** commit and push frequently. The integration person pulls the branches and merges them in dependency order. Do not merge unfinished branches blindly.
- **When a laptop dies:** push anything that exists first if possible; on the other laptop, fetch the branch and read the latest Issue handoff. Uncommitted edits and private agent chat do not move through GitHub.
- **Credentials:** each human uses their own login/token; no credentials in `docs`, code, screenshots, or GitHub Issue comments. For a private repo, grant only the access needed to read/write Issues and work on code.

## 6. Acceptance criteria for tomorrow

1. An actual IntelliJ tool window (or documented fallback) displays the common board.
2. Two separate agent sessions read and post to the same board.
3. One agent claims a task; a duplicate claim creates a visible conflict.
4. A handoff includes summary, branch, and real pushed commit.
5. The receiving agent reads the handoff and works on a different branch; the first agent takes another task.
6. The repository builds, the demo can be repeated, and the team can explain exactly which parts are real versus staged data.

## 7. Copy-paste prompt for the friend's Claude cloud agent

> We are building **Agent Board**, a Kotlin IntelliJ IDEA plugin and Kotlin MCP tool for three coding agents on separate accounts to coordinate work through one GitHub Issue. Read the attached `AGENT_BOARD_PROJECT_BRIEF.md` in full and inspect `agtheproducer/Huddle` before editing. You are **Agent C**, owner of branch `agent/idea-plugin` and the `idea-plugin` module only. Build the IntelliJ IDEA `Agent Board` tool window shown in the linked mockup: agent presence, task ownership, conflict warning, message timeline, and handoff card with a Git commit link. First make a working UI against fixture events. Then integrate with `core` via the interfaces in `docs/protocol.md` when that branch is available. Do not edit `core` or `mcp`; request contract changes through Issue `1`. Commit and push small, working increments. After each milestone, comment on the Issue with your branch, commit ID, changed files, how to run the plugin, and questions for the other agents. Do not claim that remote cloud execution can connect to a laptop's local stdio MCP server; the cloud session's job is to produce the plugin code on its branch. Prioritize an installable, repeatable judges' demo over decorative features. Do not embed GitHub credentials in source or commit any tokens.

If your friend is assigned the MCP module instead, replace the role paragraph above with:

> You are **Agent B**, owner of `agent/mcp` and the `mcp` module only. Build a local Kotlin stdio MCP server with `get_board`, `claim_task`, `send_message`, `get_messages`, `handoff_task`, and `release_task`, using the `core` interfaces. Start with fixtures until the core contract is available. Document local JetBrains Air setup and show one real Claude/Codex tool invocation. Do not edit `idea-plugin` or `core`. A cloud coding session writes code; local agents run the final MCP demo.

## 8. Questions to settle between the two humans now

1. What is the exact GitHub repository URL, and which Issue number is the coordination board?
2. Which account takes `core`, `mcp`, and `idea-plugin`? Who merges integration?
3. Can both laptops run a JetBrains IDE with the required agent connections? Which machine will be attached to the projector?
4. What time is the submission, so we can freeze features and reserve at least an hour for integration and pitch practice?

**One-sentence pitch:** “Agent Board lets different developers' AI coding agents see each other's work, resolve overlapping tasks, and hand over real code—directly from IntelliJ IDEA.”
