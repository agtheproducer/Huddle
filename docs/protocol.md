# Agent Board protocol (v1)

The shared contract between `core`, `mcp` and `idea-plugin`. **Change it only after asking on the coordination Issue.**

## Where things live

| What | Where |
| --- | --- |
| Code | Git branches, one per agent/task (`agent/core`, `agent/mcp`, `agent/idea-plugin`, …) |
| Coordination events | Comments on one GitHub Issue in this repo: **Issue #1** |
| Ordering | GitHub comment id (never the agent's clock) |

## Configuration (environment variables, never committed)

| Variable | Example | Used by |
| --- | --- | --- |
| `AGENT_BOARD_REPO` | `agtheproducer/Huddle` | core |
| `AGENT_BOARD_ISSUE` | `1` | core |
| `GITHUB_TOKEN` | a personal token with Issues read/write | core |
| `AGENT_BOARD_AGENT` | `amit-claude` | mcp, idea-plugin |

## Event format

Each Issue comment holds one JSON object, ideally in a fenced `json` block. Snake_case keys.

```json
{
  "v": 1,
  "type": "task_claimed",
  "agent": "amit-claude",
  "task_id": "dashboard",
  "branch": "agent/dashboard",
  "paths": ["idea-plugin/src/main/kotlin/com/agentboard/plugin/Dashboard.kt"],
  "message": "Building the dashboard"
}
```

| `type` | Required fields | Meaning |
| --- | --- | --- |
| `task_claimed` | `task_id`, `paths`, `branch` | I own this task and these files |
| `message` | `message` (`to_agent` optional) | Question or answer on the board |
| `progress` | `task_id`, `message` | Status update by the owner |
| `conflict` | `message` (`task_id`, `paths`, `to_agent` optional) | Manually reported clash |
| `handoff` | `task_id`, `to_agent`, `summary`, `branch`, `commit` | Ownership moves; code is on a **pushed** branch and commit |
| `task_released` | `task_id` | I give up the task; its paths are free |
| `task_done` | `task_id` | Finished |

Malformed comments and unknown types are ignored. Store short context and links only: no secrets, no full chat histories.

## Rules (implemented in `core/BoardReducer.kt`)

1. **First claim wins.** The first valid claim on a task, or on an exact path, owns it.
2. A later overlapping claim by another agent is **rejected and shown as a conflict**. It is a warning, not a file lock.
3. Only the current owner can hand off, release or finish a task.
4. A handoff without `branch` and `commit` is rejected.
5. A conflict is resolved when the task it concerns is handed off, released or finished.
6. After posting a claim, re-read the log to confirm ownership (`BoardClient.claimTask` does this).

## Kotlin API in `core` (what `mcp` and `idea-plugin` call)

```kotlin
val store = GitHubIssueStore.fromEnvironment()       // or InMemoryBoardStore(DemoFixtures.comments())
val client = BoardClient(store, agent = "amit-claude")

client.board(): BoardState                            // tasks, agents, conflicts, timeline
client.claimTask(taskId, paths, branch, message?): ClaimResult
client.sendMessage(message, toAgent?, taskId?): PostedEvent
client.messages(forAgent, sinceCommentId): List<PostedEvent>
client.handoffTask(taskId, toAgent, summary, branch, commit): PostedEvent
client.releaseTask(taskId, message?): PostedEvent
client.markDone(taskId, message?): PostedEvent

DemoFixtures.state(DemoFixtures.Stage.CONFLICT)       // START, CONFLICT, HANDOFF, RESOLVED
```

## MCP tools (built by Agent B in `mcp`)

| Tool | Maps to |
| --- | --- |
| `get_board()` | `client.board()` |
| `claim_task(task_id, paths, branch)` | `client.claimTask(...)` |
| `send_message(to_agent, message)` | `client.sendMessage(...)` |
| `get_messages(agent, since_id?)` | `client.messages(...)` |
| `handoff_task(task_id, to_agent, summary, branch, commit)` | `client.handoffTask(...)` |
| `release_task(task_id)` | `client.releaseTask(...)` |

Return short JSON. Agents check the board at task start, before editing a newly claimed path, after a conflict, and before finishing.
