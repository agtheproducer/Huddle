# Handover: Amit's side → Julieta's laptop

`amit-claude` → `julieta-claude` and `codex`, 2026-11-21 ~19:30 UTC.
Amit's session is ending; work continues from Julieta's machine. **You now own
`mcp` and `web` as well as your own modules**, plus integration and the demo.

Everything below is verified on Amit's machine unless it says otherwise. Where
something is unverified, it says so — those are the places to be careful.

## State: all pushed, nothing local

| Branch | Tip | What |
| --- | --- | --- |
| `demo` | `2a73d06` | **The branch to use.** Everything merged, clean-built |
| `main` | `414143c` | Docs, protocol, task files |
| `agent/mcp` | `f71f370` | MCP server, merged into `demo` |
| `agent/web` | merged | Web board view, merged into `demo` |
| `agent/kmp` | `0463e45` | KMP conversion, merged into `demo` |
| tag `pre-kmp` | `3cedb0d` | Last pure-JVM state. Fallback if KMP ever causes trouble |

Nothing exists only on Amit's laptop. `git fetch` and you have it all.

## What works, and how it was checked

- **`core`** — Kotlin Multiplatform. `commonMain` holds the whole engine; `jvmMain`
  has `GitHubIssueStore`; `jsMain` has one function (the clock). 12 tests pass on
  **both** `jvmTest` and `jsNodeTest`.
- **`mcp`** — six board tools over stdio. A real `claim_task` returns
  `owned:true`; a real `send_message` posted comment `7066715559` to Issue #1 from
  Amit's account. Error wrapping proven against a live GitHub 403.
- **`web`** — read-only board as a chat, pushed to the browser over SSE. Verified
  against Issue #1 with and without a token.
- **`idea-plugin`**, **`cli`** — yours; both build against the multiplatform core
  with no change to their build files.

**Verify with this, not `./gradlew build`:**

```bash
./gradlew clean build :idea-plugin:buildPlugin :mcp:installDist :cli:installDist :web:installDist
```

`build` alone compiles `idea-plugin` but **never runs `buildPlugin`** — checked
twice with `--dry-run`, where `buildPlugin` appears zero times. So it produces no
installable zip. Your green `build` on Julieta's laptop did prove the merge is
safe; it just never made the artifact.

## Set-up traps that cost time on Amit's machine

Both may already be fine on Julieta's. Check before assuming.

1. **JDK 21 is required and JDK 25 will not do.** Gradle asks for 21 exactly. If
   `/usr/libexec/java_home -V` does not list a 21, install Temurin 21 (unpack into
   `~/Library/Java/JavaVirtualMachines/`, no admin rights needed).
2. **Xcode licence blocks `git` on a fresh Mac** — every git command fails until
   `sudo xcodebuild -license` is accepted. Needs the user's password.

## Credentials

Each person uses their **own** fine-grained token with **Issues: Read and write**.
A token with no permissions authenticates fine and then fails every write with
`403 Resource not accessible by personal access token` — that cost us a while.

Never put a token in a file or a command line. In zsh:

```bash
read -rs "GITHUB_TOKEN?Token: " && export GITHUB_TOKEN
```

**The rate limit is per IP address, not per machine.** Unauthenticated clients get
60 requests an hour *shared across everyone on the same wifi*. One tokenless board
view polling once a minute consumes the whole allowance and GitHub then answers
403 to both laptops. Measured, not guessed. **On demo day every component uses a
token** — tool window, MCP server, CLI, web view. With a token it is 5000 an hour
per account.

## Which Issue

Per Julieta's correction (comment `7067711585`):

| Issue | Use |
| --- | --- |
| **#2** | Demo and rehearsal. Starts empty so judges see only real events |
| **#3** | `replay-demo` fixture traffic, the backup |
| **#1** | History. No new demo traffic |

**Amit never confirmed this with the 👍 that was asked for.** The docs in this repo
have been updated to say #2 on the assumption it stands. If it does not, grep for
`AGENT_BOARD_ISSUE=2`.

## Known problems, not fixed

1. **The MCP server does not exit on stdin EOF.** It waits forever and must be
   killed. Neither `session.onClose` nor `transport.onClose` fires in Kotlin MCP
   SDK 0.16.0, and the transport internals needed to detect it are private. An
   attempted fix did not work and was reverted rather than left in looking like
   one. **It does not affect IDEs or Claude Code**, which kill the process on
   disconnect; it only shows up when piping input by hand. Clear strays with
   `pkill -f "install/mcp"`.
2. **`InMemoryBoardStore` lost `@Synchronized`** in the KMP move — that annotation
   is JVM-only. It is used for fixtures and tests, which are single-threaded, so
   this is believed harmless, but nobody has stress-tested it.
3. **A tool call is answered asynchronously.** A shell test that closes stdin
   immediately sees only the `initialize` reply and looks broken. Real clients are
   unaffected. `mcp/README.md` explains it.

## Not built: live agent-to-agent conversation

Amit wanted his agent to ask Julieta's agent a question mid-task and get an answer
back, inside IntelliJ. The design was agreed but **never implemented** — his
session ended first. If you want it, it lives in `mcp/` and needs two tools:

- **`ask_agent(to_agent, question)`** — posts the question, then blocks, polling
  the board every ~2s until a reply addressed to this agent arrives, and returns
  it as the tool result. One call, and the teammate's answer comes back.
- **`wait_for_message()`** — the other side. Blocks until something addressed to
  this agent arrives, so an agent can sit waiting while its human works.

Build them against `BoardClient.messages(forAgent, sinceCommentId)`, which already
does the filtering. **Return after ~45 seconds with "nothing yet, call again"**
rather than hanging: MCP clients usually time a tool call out at 60s.

The honest limit: an MCP server cannot wake a sleeping agent. The other agent must
be running and must have called `wait_for_message`. That is one instruction from
its human at the start, not per message.

## What has never been rehearsed

Everything is proven **except the thing the pitch is about**: two live agents, a
real conflict, and a handoff carrying a real pushed commit, driven end to end in
order. Julieta's script is the right shape — a small real task on
`docs/demo/NOTES.md`, branch `agent/demo-notes`, with a genuinely pushed commit so
the handoff card links to real code.

Do that before anything else. It is the only remaining risk.

## Still unanswered, from the team's own plan

1. **What exactly earns the JetBrains prize?** Still unasked. `docs/TEAM_PLAN.md`
   has had this as an open question all evening. Amit said Kotlin Multiplatform is
   required, which is why `core` was converted — but nobody has confirmed it with
   staff.
2. **Submission time** — `docs/TEAM_PLAN.md` still says `[SUBMISSION_TIME]`.
3. **Which laptop is on the projector**, showing the tool window or `web`.
4. **When to freeze features.** `julieta-claude` proposed right after the first
   clean rehearsal. That seems right.

## Running each piece

JDK 21, your own token, from the repo root on `demo`:

```bash
export AGENT_BOARD_REPO=agtheproducer/Huddle
export AGENT_BOARD_ISSUE=2
export AGENT_BOARD_AGENT=julieta-claude        # or codex
read -rs "GITHUB_TOKEN?Token: " && export GITHUB_TOKEN
```

| Piece | Command |
| --- | --- |
| Tool window | `./gradlew :idea-plugin:runIde` → View → Tool Windows → Agent Board |
| CLI | `./gradlew :cli:run --args="board"` |
| Web view | `./gradlew :web:installDist && web/build/install/web/bin/web` → http://localhost:8080 |
| MCP server | `./gradlew :mcp:installDist`, then [`mcp/README.md`](../../mcp/README.md) to connect Junie or Claude Code |
| Post to the board | `./mcp/live-check.sh "your message"` |

Good luck. The hard parts are done and verified; what is left is rehearsal and the
four questions above.
