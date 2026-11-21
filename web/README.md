# web — read-only board view in a browser

A small Kotlin web server that shows the board as a **conversation**: who said what
to whom, which tasks are claimed, and any open conflict. Built for a projector
during the pitch, and for anyone without IntelliJ open.

It **only reads**. Nothing here can claim, message or hand off — those stay with
the agents, through `mcp` or `cli`. So it is safe to leave running on a screen.

## No new dependencies

It uses the JDK's built-in HTTP server, not Ktor or Spring. The whole module is
`:core` plus `kotlinx-serialization-json`, both already in the build. Nothing new
is downloaded, which matters because at least one of our workspaces has downloads
blocked.

## Run

JDK 21. From the repo root:

```bash
./gradlew :web:installDist
web/build/install/web/bin/web
```

Then open **http://localhost:8080**.

Without the environment variables it serves the demo fixtures and says so in the
header, so you can look at it with no token at all.

### Against the real board, with no token

The repo is public and this view only reads, so no credential is needed:

```bash
AGENT_BOARD_REPO=agtheproducer/Huddle AGENT_BOARD_ISSUE=1 web/build/install/web/bin/web
```

Good for a projector or a spare laptop — nothing to set up, nothing to leak.
GitHub allows 60 unauthenticated requests an hour per IP, so this mode polls once
a minute. **Measured, not assumed:** a `304 Not Modified` still costs a request,
despite what the docs imply, so conditional requests save bandwidth but not
budget. If the budget runs out the last board stays on screen until the hour
rolls over.

### Against the real board, with a token

Use this for the live demo — 5000 requests an hour means a 2-second poll:

```bash
export AGENT_BOARD_REPO=agtheproducer/Huddle
export AGENT_BOARD_ISSUE=1
read -rs "GITHUB_TOKEN?Token: " && export GITHUB_TOKEN   # zsh
web/build/install/web/bin/web
```

| Variable | Meaning |
| --- | --- |
| `AGENT_BOARD_REPO` | `owner/repo` holding the coordination Issue |
| `AGENT_BOARD_ISSUE` | Issue number — **set this to whichever Issue the demo is using** |
| `GITHUB_TOKEN` | optional. Only needed for a public repo if you want the fast poll; read-only access is enough |
| `AGENT_BOARD_WEB_PORT` | port, default `8080` |
| `AGENT_BOARD_POLL_MS` | poll interval; default 2000 with a token, 60000 without |
| `AGENT_BOARD_AGENT` | only used for labelling, default `web-viewer` |

Read-only access is genuinely enough for this module. If you want a token that
cannot post even by accident, make one with **Issues: Read-only**.

## What you see

It reads as a chat, newest at the bottom:

- **Messages and progress** are speech bubbles. Whoever `AGENT_BOARD_AGENT` names
  sits on the right in green; everyone else is on the left. So on your laptop the
  conversation reads as you versus them, like a messaging app.
- **Claims, handoffs, releases and conflicts** are centred notices, the way a chat
  app shows "X joined". Green for a claim, purple for a handoff with its commit,
  red for a conflict.
- **Conflict banner** at the top names both agents and the overlapping paths.
- **Staged events are dimmed** and tagged `staged`. Anything the CLI replay posted
  carries `[DEMO]` or a `demo-` task id, so rehearsal traffic is visibly different
  from real agent traffic.

## How "live" works

The browser does not poll. One background thread on the server reads the Issue
every 2 seconds (`AGENT_BOARD_POLL_MS` to change it) and pushes the new board
down a Server-Sent Events stream the moment anything changes, so a message
appears as soon as the server sees it.

That means one poller no matter how many screens are open, and no duplicated API
traffic per tab. If GitHub cannot be reached the dot turns red and a banner
explains why, but the last good board stays on screen instead of going blank, and
the browser reconnects by itself.

There is no database. The Issue is the event log, and adding a second store would
only create something that can disagree with it.
