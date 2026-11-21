# mcp — owner: Agent B (branch `agent/mcp`)

Local **stdio MCP server** in Kotlin. It puts the Agent Board in front of any
MCP-capable coding agent as eight tools, so the agent claims work, sees conflicts
and hands over code without the developer relaying anything by hand.

Contract: [`docs/protocol.md`](../docs/protocol.md). Tasks: [`docs/tasks/amit.md`](../docs/tasks/amit.md).

## The tools

| Tool | When the agent calls it |
| --- | --- |
| `get_board` | Start of every task, and before touching a new file. |
| `claim_task` | Before editing. If `owned=false`, do not edit — talk to the owner. |
| `send_message` | Ask what someone built, agree who continues, answer a question. |
| `get_messages` | After a conflict, while waiting, and before finishing. |
| `handoff_task` | Give a task away. Needs a pushed branch **and** commit. |
| `release_task` | Drop a task so its files are free again. |
| `ask_agent` | Ask another agent a question and **wait** for its reply (up to ~45 s). |
| `wait_for_message` | **Wait** until something addressed to you arrives (up to ~45 s), then call it again. |

### Talking back and forth

`ask_agent` posts the question and blocks until the other agent answers, so one
call gets the reply. The other agent stays reachable by calling
`wait_for_message` in a loop and answering with `send_message`. Tell it once at
the start, for example: *"Stay on the Agent Board: call wait_for_message, answer
what arrives with send_message, and call it again."*

Both return after about 45 seconds with `answered=false` / `timed_out=true`
rather than hanging, because MCP clients usually give up on a tool call at 60 s.
An MCP server cannot wake an agent that is not running: the other agent must be
in a session and waiting. Each waiting call polls the Issue every 2 s, well
inside the 5000 requests an hour a token allows.

## Build

Needs **JDK 21** (JDK 25 alone will not work — Gradle looks for 21 specifically).

```bash
./gradlew :mcp:installDist
```

That produces the launcher the agents run:

```
mcp/build/install/mcp/bin/mcp
```

Rebuild after every code change — the agents run the built copy, not the source.

## Two modes

The server picks its mode from the environment, with no flag to set:

- **Live** — when `AGENT_BOARD_REPO`, `AGENT_BOARD_ISSUE` and `GITHUB_TOKEN` are all
  set, it reads and writes real comments on the coordination Issue.
- **Fixtures** — when any of them is missing, it serves demo data in memory and
  **posts nothing to GitHub**. Good for trying things out safely.

It logs which mode it chose to stderr at startup.

| Variable | Example | Needed for live mode |
| --- | --- | --- |
| `AGENT_BOARD_REPO` | `agtheproducer/Huddle` | yes |
| `AGENT_BOARD_ISSUE` | `1` | yes |
| `GITHUB_TOKEN` | fine-grained token, Issues read/write | yes |
| `AGENT_BOARD_AGENT` | `amit-claude` | no — defaults to `amit-claude` |

Every laptop uses its **own** token and its **own** `AGENT_BOARD_AGENT`. Amit is
`amit-claude`, Julieta is `julieta-claude`, her second agent is `julieta-codex`.
Two agents sharing an id makes the board lie about who owns what.

**Never commit a token.** Put it in the IDE's MCP settings or your shell profile.

## Connect

Use the **absolute** path to the launcher everywhere below. Print it with:

```bash
echo "$PWD/mcp/build/install/mcp/bin/mcp"
```

### JetBrains AI Assistant / Junie

1. **Settings → Tools → AI Assistant → Model Context Protocol (MCP) → Add**.
2. Choose a command-line server and fill in:
   - **Command:** the absolute path you printed above
   - **Arguments:** leave empty
   - **Environment variables:** `AGENT_BOARD_REPO`, `AGENT_BOARD_ISSUE`,
     `GITHUB_TOKEN`, `AGENT_BOARD_AGENT`
3. For the agent in the AI chat to be allowed to use it, turn on
   **Pass custom MCP servers** in **Settings → Tools → AI Assistant → Agents**.
4. Reopen the chat. The eight tools should be offered to the agent.

### Claude Code

One command, from the repo root:

```bash
claude mcp add agent-board --env AGENT_BOARD_REPO=agtheproducer/Huddle --env AGENT_BOARD_ISSUE=1 --env AGENT_BOARD_AGENT=amit-claude --env GITHUB_TOKEN=$GITHUB_TOKEN -- "$PWD/mcp/build/install/mcp/bin/mcp"
```

Export your token first (`export GITHUB_TOKEN=...`) so it never gets typed into a
file. Check it registered with `claude mcp list`.

Leave every `--env` off to run against fixtures instead.

## Check it works

The server speaks JSON-RPC on stdin/stdout. This asks it to claim a task:

```bash
BIN="$PWD/mcp/build/install/mcp/bin/mcp"
{ printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"claim_task","arguments":{"task_id":"dashboard","paths":["Dashboard.kt"],"branch":"agent/mcp"}}}'
  sleep 5; } | "$BIN"
```

You should get three lines back, the last one containing `"owned":true`.

**The `sleep 5` matters.** Tool calls are answered asynchronously. If stdin
closes the moment the last line is written, the server has not answered yet and
you see only the `initialize` reply — which looks like a broken tool call but is
just the test hanging up too early. Real clients hold the connection open, so
this only ever bites in hand-written shell tests.

Press `Ctrl-C` to stop it.

## Known issue

When stdin reaches end-of-file, the server does **not** exit — it waits forever
and has to be killed. Neither `session.onClose` nor `transport.onClose` fires on
EOF in Kotlin MCP SDK 0.16.0, so there is no clean fix in our code.

It does not affect normal use: IDEs and Claude Code terminate the process when
they disconnect. It only shows up when piping input by hand. If you leave stray
servers running:

```bash
pkill -f "install/mcp"
```

## Scope

Do not edit `core` or `idea-plugin` from this branch. Ask on the coordination
Issue for contract changes.
