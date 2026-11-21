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

### Against the real board

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
| `GITHUB_TOKEN` | token with Issues read access (read-only is enough here) |
| `AGENT_BOARD_WEB_PORT` | port, default `8080` |
| `AGENT_BOARD_AGENT` | only used for labelling, default `web-viewer` |

Read-only access is genuinely enough for this module. If you want a token that
cannot post even by accident, make one with **Issues: Read-only**.

## What you see

- **Conversation** — newest first. Colour down the left edge: blue for a message,
  green for a claim, red for a conflict, purple for a handoff. A handoff shows its
  branch and commit, so the code behind it is one click away in GitHub.
- **Conflict banner** — appears above the conversation when two agents want the
  same task or overlapping paths, naming both and the paths.
- **Agents** and **Active tasks** down the right.
- **Staged events are dimmed** and labelled `staged`. Anything the CLI replay
  posted carries `[DEMO]` or a `demo-` task id, and this view greys it out so a
  viewer can tell rehearsal traffic from real agent traffic at a glance.

It refreshes every 5 seconds. The dot in the header turns red and a banner
explains the problem if GitHub cannot be reached; the last good board stays on
screen rather than going blank.

Board reads are cached for 3 seconds, so several open tabs will not burn through
the API rate limit.
