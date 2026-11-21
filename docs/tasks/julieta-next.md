# For Julieta, `julieta-claude` and `codex` — integration is done, here's what's left

Written by `amit-claude` at 17:55 UTC on 2026-11-21, while Amit and Julieta are at dinner.
Everything below is verified on this machine, not assumed. Branch names and commit
ids are real and pushed.

## The short version

The `demo` branch exists, has all three agents' work in it, and builds green.
Nothing is blocked on Amit. The next move is yours.

## What is on `demo`

Created from `main` (`4c59f12`) and merged in the order `julieta-claude` proposed:

| Merged | Branch | Tip |
| --- | --- | --- |
| 1 | `agent/core` | `7d538e1` |
| 2 | `agent/mcp` | `f71f370` |
| 3 | `agent/idea-plugin` | `fcd95cc` |

Demo tip: `db0dcc4`. **No merge conflicts** — the three branches touch disjoint
files, and only `agent/core` edits `settings.gradle.kts`.

Verified on the merged tree, with JDK 21:

```
./gradlew :core:test :mcp:test :mcp:installDist :cli:assemble :idea-plugin:buildPlugin
BUILD SUCCESSFUL in 4m 24s
```

- `core`: 12 tests, 0 failures
- `mcp`: 6 tests, 0 failures
- `idea-plugin`: plugin zip builds
- `cli`: assembles

The `mcp` tools were re-checked against Codex's rewritten conflict logic
(`PathOverlap.kt`): `claim_task` on a **folder** path still returns `owned:true`
through the MCP server. The change did not break the tool layer.

## Answers to `julieta-claude`'s two questions

**1. "`docs/protocol.md` still says `Issue #[ISSUE_NUMBER]`, can the owner set it to #1?"**

Done, before you asked — commit `4c59f12` on `main`. Five files were corrected in
the same commit: the repo name was also stale (`agtheproducer/impact_ledger`, from
before the rename to `Huddle`). `git pull` on `main` and you'll have it.
Mentions of the `archive/impact-ledger` **branch** were deliberately left alone;
that branch really is still called that.

**2. "Merge order `agent/core` → `agent/mcp` → `agent/idea-plugin`?"**

Yes. Used exactly that, and it merged clean. Your reasoning was right, and your
correction to Task 3 was right too: `docs/tasks/amit.md` listed only `agent/mcp`
and `agent/idea-plugin`, which would have produced a demo with no `cli` module and
without the folder-overlap fix. That file predates `agent/core`.

## What we need from you next

**1. Run your own build check on `demo`.** `julieta-claude` offered to do this —
please go ahead:

```bash
git fetch && git checkout demo && ./gradlew build
```

`./gradlew build` is wider than what was run here (it adds `check` on every
module). If it finds core or CLI integration failures, fix them **on `demo`**
and push, rather than on the feature branches — the branches have served their
purpose now.

**2. Decide what happens to the demo replay events.** Issue #1 currently holds
15 comments, and **nine of them are `[DEMO]` replay events** posted by
`./gradlew :cli:run --args="replay-demo"`. Three of those claim to be from
`amit-claude` and were not sent by Amit's agent.

`cli/README.md` is already clear that the replay is a display safety net and must
not be presented as real agent-to-agent communication, which is the right call.
The practical problem is narrower: `get_board` returns the **last ten events**, and
the IntelliJ tool window shows a recent timeline. Right now both are dominated by
staged content, so a judge looking at the live board mostly sees `[DEMO]` rows.

Two options, your call:
- point `replay-demo` at a second, throwaway Issue, and keep #1 for real events; or
- keep one Issue and accept that the board reads as mostly staged until more real
  traffic lands.

There is no need to delete comments — the Issue body asks people not to, and the
event log is append-only by design.

**3. Confirm the demo script owner for each step.** The four beats are
claim → conflict → handoff → resolved. We have proven the machinery:
`amit-claude` posts from Amit's laptop and account, `julieta-claude` posts from
yours, and your tool window picks up a new message in about 4 seconds. What has
**not** been rehearsed end to end is the two of you driving those four beats live,
in order, in front of someone.

## Open questions for your agents

1. **Does the tool window need anything from `mcp`?** Both read the same Issue
   through `core`, so they should be independent — but if `idea-plugin` expects an
   event shape the MCP tools don't emit, now is the time to say so, on the board.
2. **Whose agent id drives the demo?** If both laptops run with
   `AGENT_BOARD_AGENT=amit-claude` by mistake, the board will show one agent
   talking to itself. Worth each of you confirming your own id before the run.
3. **Is `release_task` part of the script?** It exists in `mcp` and in the CLI but
   does not appear in the four-beat story. If it is not demoed, that is fine —
   just don't let it be discovered live.

## Known issue, not worth fixing tonight

The MCP server does not exit when its stdin reaches end-of-file; it waits forever
and has to be killed. Neither `session.onClose` nor `transport.onClose` fires on
EOF in Kotlin MCP SDK 0.16.0, and the transport internals that would let us detect
it are private. An attempted fix did not work and was reverted rather than left in
place looking like a fix.

It does not affect IDEs or Claude Code, which terminate the process on disconnect.
It only shows up when piping input by hand. Clear strays with `pkill -f "install/mcp"`.

## How to run each piece

All three need **JDK 21** and your own token. Never commit a token.

```bash
export AGENT_BOARD_REPO=agtheproducer/Huddle
export AGENT_BOARD_ISSUE=2
export AGENT_BOARD_AGENT=julieta-claude     # or codex
read -rs "GITHUB_TOKEN?Token: " && export GITHUB_TOKEN   # zsh
```

| Piece | Command |
| --- | --- |
| Tool window | `./gradlew :idea-plugin:runIde`, then View → Tool Windows → Agent Board |
| CLI | `./gradlew :cli:run --args="board"` |
| MCP server | `./gradlew :mcp:installDist`, then see [`mcp/README.md`](../../mcp/README.md) to connect it to Junie or Claude Code |
| MCP smoke test | `./mcp/live-check.sh` — posts one real message and says plainly whether GitHub accepted it |

A fine-grained token needs **Issues: Read and write**. If it is left at Read-only
you get `403 Resource not accessible by personal access token`; the server reports
that as a clean `{"error": ...}` rather than crashing.
