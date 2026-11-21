# idea-plugin — owner: Agent C (branch `agent/idea-plugin`)

IntelliJ IDEA plugin in Kotlin with an **Agent Board** tool window.

**Full instructions: [`docs/tasks/julieta.md`](../docs/tasks/julieta.md).** The build file, `plugin.xml` and a starter tool window already work.

## To build

1. `./gradlew :idea-plugin:runIde` opens a test IntelliJ with the plugin (Gradle downloads IntelliJ; no install needed).
2. Build the tool window against fixtures first: `DemoFixtures.state(DemoFixtures.Stage.START | CONFLICT | HANDOFF | RESOLVED)`.
4. Show: agent cards (task, branch, last seen), task cards (owner, paths, status), conflict banner, conversation timeline, handoff card with commit link.
5. Then read live data with `BoardClient(GitHubIssueStore.fromEnvironment(), agent)` and refresh every few seconds.

**Gate:** a message posted from the other laptop appears in the tool window after refresh.

Do not edit `core` or `mcp`. Ask on the coordination Issue for contract changes.
