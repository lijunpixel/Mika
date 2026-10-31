package com.wangzan101.mika.mcp

import com.wangzan101.mika.collaboration.CollaborationRuntime
import com.wangzan101.mika.core.collaboration.CollaborationRequest
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.mcp.McpToolExtension
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

/** Remote callers may inspect availability and run a local, zero-spend demonstration.
 * Real-provider and phone permissions must be configured and started on the phone. */
class CollaborationMcpExtension(private val runtime: CollaborationRuntime) : McpToolExtension {
    override val tools = listOf(
        McpTaskTools.Tool("ibot_list_agents", "List configured AI connections without credentials. Installed apps do not imply an API connection.",
            Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject, true),
        McpTaskTools.Tool("ibot_run_collaboration", "Run a zero-spend Mock collaboration. request uses CollaborationRequest fields. Real-provider calls and phone grants must be started on the phone. Follow the task_id with mika_task_status, mika_wait_task or mika_cancel_task.",
            Json.parseToJsonElement("""{"type":"object","properties":{"request":{"type":"object"}},"required":["request"],"additionalProperties":false}""").jsonObject, false),
    )

    override suspend fun call(name: String, args: JsonObject): JsonObject {
        try {
            return when (name) {
                "ibot_list_agents" -> {
                    require(args.isEmpty()) { "No arguments expected" }
                    runtime.refreshAgents()
                    buildJsonObject { put("agents", Json.parseToJsonElement(Json.encodeToString(runtime.agents.value))) }
                }
                "ibot_run_collaboration" -> {
                    require(args.keys == setOf("request")) { "Expected request" }
                    val request = Json.decodeFromJsonElement<CollaborationRequest>(args.getValue("request"))
                    require(request.mockOnly && request.limits.maxCostMicros == 0L && !request.allowPhoneExecution) {
                        "Real-provider and phone permissions require starting this task in ibot on the phone"
                    }
                    require(request.goal.isNotBlank() && request.goal.length <= 12000) { "Invalid task length" }
                    require(request.assignments.size <= 5 && request.limits.maxRequests in 1..20 && request.limits.maxConcurrent in 1..2) { "Remote task limits exceeded" }
                    require(request.limits.maxTokens in 1..32000 && request.limits.maxOutputTokens in 1..2048 &&
                        request.limits.maxInputBytes in 1..32000 && request.limits.taskTimeoutMillis in 1..600000 &&
                        request.limits.requestTimeoutMillis in 1..120000) { "Remote budget limits exceeded" }
                    runtime.useMockAgents(true)
                    buildJsonObject { put("task_id", runtime.start(request)); put("mock", true) }
                }
                else -> throw McpTaskTools.ToolError("Unknown ibot tool")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: McpTaskTools.ToolError) { throw e }
        catch (_: Exception) { throw McpTaskTools.ToolError("Invalid or unavailable collaboration request; check the phone") }
    }
}
