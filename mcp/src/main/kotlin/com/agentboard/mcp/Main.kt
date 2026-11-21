package com.agentboard.mcp

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardStore
import com.agentboard.core.DemoFixtures
import com.agentboard.core.GitHubIssueStore
import com.agentboard.core.HttpBoardStore
import com.agentboard.core.InMemoryBoardStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val LIVE_VARS = listOf("AGENT_BOARD_REPO", "AGENT_BOARD_ISSUE", "GITHUB_TOKEN")

/**
 * Create the BoardStore based on environment variables.
 * Priority: AGENT_BOARD_HTTP_URL > GitHub vars > fixtures
 */
fun createStore(env: Map<String, String> = System.getenv()): BoardStore {
    // HTTP server takes priority if configured
    val httpUrl = env["AGENT_BOARD_HTTP_URL"]
    if (!httpUrl.isNullOrBlank()) {
        return HttpBoardStore(httpUrl)
    }
    // Fall back to GitHub if configured
    val live = LIVE_VARS.all { !env[it].isNullOrBlank() }
    if (live) {
        return GitHubIssueStore.fromEnvironment(env)
    }
    // Default to demo fixtures
    return InMemoryBoardStore(DemoFixtures.comments())
}

/** Live GitHub Issue when the env vars are set, otherwise the demo fixtures in memory. */
fun createTools(env: Map<String, String> = System.getenv()): BoardTools {
    val agent = env["AGENT_BOARD_AGENT"]?.takeIf { it.isNotBlank() } ?: "amit-claude"
    val store = createStore(env)
    val mode = when {
        !env["AGENT_BOARD_HTTP_URL"].isNullOrBlank() -> "http: ${env["AGENT_BOARD_HTTP_URL"]}"
        LIVE_VARS.all { !env[it].isNullOrBlank() } -> "live: ${env["AGENT_BOARD_REPO"]} issue #${env["AGENT_BOARD_ISSUE"]}"
        else -> "fixtures (demo data, nothing is posted to GitHub)"
    }
    return BoardTools(BoardClient(store, agent), mode)
}

/** stdout carries the MCP protocol, so all logging goes to stderr. */
private fun log(message: String) = System.err.println("[agent-board] $message")

fun main(): Unit = runBlocking {
    val tools = createTools()
    log("starting as ${tools.agent}")

    val server = Server(
        Implementation(name = "agent-board", version = "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
    registerTools(server, tools)

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

fun registerTools(server: Server, tools: BoardTools) {
    fun tool(
        name: String,
        description: String,
        schema: ToolSchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList()),
        run: (JsonObject?) -> JsonObject,
    ) {
        server.addTool(name = name, description = description, inputSchema = schema) { request ->
            val result = try {
                withContext(Dispatchers.IO) { run(request.arguments) }
            } catch (e: ToolInputException) {
                buildJsonObject { put("error", e.message) }
            } catch (e: Exception) {
                log("$name failed: ${e.message}")
                buildJsonObject { put("error", "${e::class.simpleName}: ${e.message}") }
            }
            CallToolResult(content = listOf(TextContent(result.toString())), isError = "error" in result)
        }
    }

    tool(
        "get_board",
        "Read the shared Agent Board: who owns which task and files, open conflicts, and recent messages. " +
            "Call this at the start of every task and before editing a new file.",
    ) { tools.getBoard() }

    tool(
        "claim_task",
        "Claim a task and the files or folders you will edit, BEFORE editing them. " +
            "If owned=false, another agent already owns them: do not edit, use send_message to agree who continues.",
        schema(
            required = listOf("task_id", "paths", "branch"),
            "task_id" to str("Short task id, e.g. 'dashboard'"),
            "paths" to strArray("Repo-relative files or folders you will edit"),
            "branch" to str("Git branch you will push to"),
            "message" to str("One line: what you are about to do"),
        ),
    ) { tools.claimTask(it) }

    tool(
        "send_message",
        "Post a message on the board to another agent (or everyone): ask what they built, agree who continues, answer questions.",
        schema(
            required = listOf("message"),
            "message" to str("The message"),
            "to_agent" to str("Agent id to address, e.g. 'julieta-claude'. Omit to address everyone."),
            "task_id" to str("Task the message is about"),
        ),
    ) { tools.sendMessage(it) }

    tool(
        "get_messages",
        "Read messages, handoffs and conflicts addressed to you. Check after a conflict, while waiting for an answer, and before finishing.",
        schema(
            required = emptyList(),
            "since_id" to num("Only return events after this comment id (use last_comment_id from the previous call)"),
        ),
    ) { tools.getMessages(it) }

    tool(
        "ask_agent",
        "Ask another agent a question on the board and WAIT for its reply (up to ~45 s). Returns the reply, " +
            "or answered=false with a since_id to keep waiting via wait_for_message. The other agent must be running.",
        schema(
            required = listOf("to_agent", "question"),
            "to_agent" to str("Agent id to ask, e.g. 'amit-claude'"),
            "question" to str("The question"),
            "task_id" to str("Task the question is about"),
            "timeout_seconds" to num("How long to wait for the reply, at most 45 (default 45)"),
        ),
    ) { tools.askAgent(it) }

    tool(
        "wait_for_message",
        "WAIT until a message, handoff or conflict addressed to you arrives (up to ~45 s), then return it. " +
            "Use it to stay available to the other agent: when it returns timed_out=true, call it again. " +
            "Answer questions with send_message.",
        schema(
            required = emptyList(),
            "since_id" to num("Only events after this comment id. Omit to get what is new since your last message call"),
            "timeout_seconds" to num("How long to wait, at most 45 (default 45)"),
        ),
    ) { tools.waitForMessage(it) }

    tool(
        "handoff_task",
        "Hand a task you own to another agent. First commit and push your work: the handoff must include the pushed branch and commit id.",
        schema(
            required = listOf("task_id", "to_agent", "summary", "branch", "commit"),
            "task_id" to str("Task you own"),
            "to_agent" to str("Agent id taking over"),
            "summary" to str("What is done, what is left, anything the next agent must know"),
            "branch" to str("Pushed branch with the work"),
            "commit" to str("Pushed commit id"),
        ),
    ) { tools.handoffTask(it) }

    tool(
        "release_task",
        "Give up a task you own so its files are free for other agents.",
        schema(
            required = listOf("task_id"),
            "task_id" to str("Task you own"),
            "message" to str("Why you are releasing it"),
        ),
    ) { tools.releaseTask(it) }
}

// --- schema helpers --------------------------------------------------------

private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
private fun num(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
private fun strArray(description: String) = buildJsonObject {
    put("type", "array")
    putJsonObject("items") { put("type", "string") }
    put("description", description)
}

private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) =
    ToolSchema(properties = JsonObject(props.toMap()), required = required)
