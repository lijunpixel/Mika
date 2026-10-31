package com.wangzan101.mika.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.collaboration.*
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.mcp.CollaborationMcpExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationMcpTest {
    private val runtime get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp).collaboration

    @Test fun mockTasksAreTraceableAndExistingAppsAreNeverApiConnections() = runBlocking {
        val endpoint = CollaborationMcpExtension(runtime)
        val agents = endpoint.call("ibot_list_agents", JsonObject(emptyMap()))["agents"]!!.jsonArray
        val bot = agents.first { it.jsonObject["id"]!!.jsonPrimitive.content == "grok-bot" }.jsonObject
        assertFalse(bot["configured"]!!.jsonPrimitive.boolean)
        val request = CollaborationRequest("Local Mock MCP regression; do not call a service", CollaborationMode.Parallel,
            listOf(RoleAssignment(AgentRole.Researcher, "mock-researcher"), RoleAssignment(AgentRole.Reviewer, "mock-reviewer")), "mock-coordinator")
        val started = endpoint.call("ibot_run_collaboration", buildJsonObject { put("request", Json.parseToJsonElement(Json.encodeToString(request))) })
        val id = started["task_id"]!!.jsonPrimitive.content
        withTimeout(20000) { while (runtime.get(id)?.status in setOf(CollaborationStatus.Queued, CollaborationStatus.Running)) delay(30) }
        val result = runtime.get(id)!!.result!!
        assertEquals(CollaborationStatus.Succeeded, result.status)
        assertEquals(3, result.outputs.size)
        assertTrue(result.outputs.all { it.agentId.startsWith("mock-") && !it.verified })
    }

    @Test fun remoteRequestsCannotGrantRealProviderOrPhonePermissions() = runBlocking {
        val endpoint = CollaborationMcpExtension(runtime)
        for (request in listOf(
            CollaborationRequest("not authorized", CollaborationMode.Single, emptyList(), "chatgpt", mockOnly = false),
            CollaborationRequest("not authorized", CollaborationMode.Single, emptyList(), "mock-coordinator", allowPhoneExecution = true),
        )) {
            try {
                endpoint.call("ibot_run_collaboration", buildJsonObject { put("request", Json.parseToJsonElement(Json.encodeToString(request))) })
                fail("remote permissions accepted")
            } catch (_: McpTaskTools.ToolError) { }
        }
    }
}
