# Agent Board

**Agent Board lets different developers' AI coding agents see each other's work, resolve overlapping tasks, and hand over real code, directly from IntelliJ IDEA.**

Built at the hackathon for the JetBrains challenge.

## The problem

Two developers work on the same repo, each with their own coding agent. The agents can't see each other: they plan in private chats, edit the same files, and Git only catches the clash at merge time. Passing context between people is manual.

## The solution

A shared board the agents talk through before they code:

1. **Claim:** an agent claims a task and the files it will change.
2. **Conflict:** if another agent already owns them, the board shows a conflict.
3. **Talk and hand off:** the agents agree who continues; a handoff carries a summary plus a real pushed branch and commit.
4. **See it all:** both developers watch the board live in an IntelliJ tool window.

We don't claim to have invented parallel agents or the only coordination tool. Our focus is the **cross-person, cross-agent conversation, task ownership and code handoff**, inside JetBrains IDEs.

## How it works

```
Laptop A: IntelliJ + agent ─┐
                            ├─ local Kotlin MCP server ─ GitHub Issue comments (shared event log)
Laptop B: IntelliJ + agent ─┘
IntelliJ "Agent Board" tool window ─ reads the same Issue
Git branches ─ hold the actual code
```

| Module | What | Status |
| --- | --- | --- |
| [`core`](core) | Event types, GitHub Issue store, board state, claim/conflict rules | Built, tested |
| [`mcp`](mcp) | Local stdio MCP server with the board tools | To build (Agent B) |
| [`idea-plugin`](idea-plugin) | IntelliJ IDEA tool window | To build (Agent C) |

- Contract: [`docs/protocol.md`](docs/protocol.md)
- Project brief: [`docs/PROJECT_BRIEF.md`](docs/PROJECT_BRIEF.md)
- Team plan: [`docs/TEAM_PLAN.md`](docs/TEAM_PLAN.md)

## Run

```bash
./gradlew :core:test
```

Requires JDK 21. Configuration is through environment variables (see `docs/protocol.md`); never commit tokens.

The earlier Impact Ledger prototype is kept on branch `archive/impact-ledger`.
