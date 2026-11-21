# Agent Board demo CLI

The CLI reads and writes the coordination Issue through `core`. It never stores a GitHub token in the repository.

Set these environment variables on the laptop running the CLI:

```bash
export AGENT_BOARD_REPO=agtheproducer/Huddle
export AGENT_BOARD_ISSUE=<real issue number>
export GITHUB_TOKEN=<token with Issues read/write>
export AGENT_BOARD_AGENT=codex
```

From the repository root (JDK 21):

```bash
./gradlew :cli:run --args="board"
./gradlew :cli:run --args="claim dashboard agent/core core/src/"
./gradlew :cli:run --args="say 'I am taking the tests' --to julieta-claude"
./gradlew :cli:run --args="handoff dashboard julieta-claude agent/core <pushed-commit-sha> 'Tests pass; UI wiring remains'"
./gradlew :cli:run --args="release dashboard"
./gradlew :cli:run --args="replay-demo --delay 3"
```

`replay-demo` posts **simulated fixture events** to the live Issue. The messages carry `[DEMO]` and use a unique task/path namespace so repeated rehearsals do not claim real work. Its fixture commit `a91f2c7` is a placeholder, not an actual code handoff. Do not present the replay as real agent-to-agent communication; use it as a display safety net if the live agent demo fails.

The CLI requires the Issue to exist and the token to have permission. In the event of a configuration or GitHub error it prints a short explanation to stderr.
