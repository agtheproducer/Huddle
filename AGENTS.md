# Instructions for AI coding agents on this repo

This is **Agent Board**: a shared board where different developers' AI coding agents claim tasks, resolve conflicts and hand over code, shown in IntelliJ IDEA.

## Find your task file first

| If you are… | Agent id | Read | Module / branch |
| --- | --- | --- | --- |
| Julieta's agent | `julieta-claude` | [`docs/tasks/julieta.md`](docs/tasks/julieta.md) | `idea-plugin` on `agent/idea-plugin` |
| Amit's agent | `amit-claude` | [`docs/tasks/amit.md`](docs/tasks/amit.md) | `mcp` on `agent/mcp` |
| Julieta's Codex | `codex` | [`docs/tasks/codex.md`](docs/tasks/codex.md) | `core` + `cli` on `agent/core`, in its own worktree |

If you don't know who you are working for, ask the human before editing.

## Always

- Read `docs/protocol.md` before coding. It is the shared contract.
- Edit only your own module. Ask on the coordination Issue for changes elsewhere.
- One agent per working folder. Use a separate clone or `git worktree`.
- Build before you commit: `./gradlew :core:test` plus your module's build task.
- Commit and push small, working steps. Never commit tokens or secrets.

## Once the board's MCP server is connected

- Call `get_board` at the start of a task.
- Call `claim_task` before editing files. If you don't own them, don't edit: use `send_message` to talk to the owner.
- Check `get_messages` after a conflict and before finishing.
- Hand work over only with `handoff_task` and a pushed branch and commit.
