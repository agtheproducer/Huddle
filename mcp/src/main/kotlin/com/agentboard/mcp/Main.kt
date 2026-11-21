package com.agentboard.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * STARTER (verified to build and answer over stdio). Amit's agent replaces this with the six
 * board tools from docs/protocol.md, see docs/tasks/amit.md.
 */
fun main(): Unit = runBlocking {
    val server = Server(
        Implementation(name = "agent-board", version = "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
    )
    server.addTool(
        name = "release_task",
        description = "Give up a claim",
        inputSchema = ToolSchema(
            properties = buildJsonObject { putJsonObject("task_id") { put("type", "string") } },
            required = listOf("task_id"),
        ),
    ) { request ->
        val taskId = request.arguments?.get("task_id")?.jsonPrimitive?.content
        CallToolResult(content = listOf(TextContent("""{"released":"$taskId"}""")))
    }
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
