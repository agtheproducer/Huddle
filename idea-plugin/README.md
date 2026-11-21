# idea-plugin — owner: Agent C (branch `agent/idea-plugin`)

IntelliJ IDEA plugin in Kotlin with an **Agent Board** tool window.

## To build

1. Create the plugin project here using the IntelliJ Platform Gradle Plugin (start from JetBrains' official plugin template), depend on `project(":core")`.
2. Add `include(":idea-plugin")` in `settings.gradle.kts`.
3. Build the tool window against fixtures first: `DemoFixtures.state(DemoFixtures.Stage.START | CONFLICT | HANDOFF | RESOLVED)`.
4. Show: agent cards (task, branch, last seen), task cards (owner, paths, status), conflict banner, conversation timeline, handoff card with commit link.
5. Then read live data with `BoardClient(GitHubIssueStore.fromEnvironment(), agent)` and refresh every few seconds.

**Gate:** a message posted from the other laptop appears in the tool window after refresh.

Do not edit `core` or `mcp`. Ask on the coordination Issue for contract changes.
