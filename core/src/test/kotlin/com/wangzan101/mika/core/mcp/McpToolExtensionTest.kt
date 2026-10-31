package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class McpToolExtensionTest {
    private var calls = 0
    private val extension = object : McpToolExtension {
        override val tools = listOf(McpTaskTools.Tool("ibot_list_agents", "test", Json.parseToJsonElement("""{"type":"object"}""").jsonObject, true))
        override suspend fun call(name: String, args: JsonObject): JsonObject {
            calls++
            return buildJsonObject { put("mock", true) }
        }
    }

    @Test fun extensionPreservesLegacyToolsAndDispatchesOnlyDeclaredNames() = runTest {
        val tools = McpTaskTools(RemoteTaskService(FakeRemoteTasks()), extension = extension)
        assertEquals(7, tools.tools.size)
        assertTrue(tools.has("mika_run_task"))
        assertTrue(tools.has("ibot_list_agents"))
        assertEquals(JsonPrimitive(true), tools.call("ibot_list_agents", JsonObject(emptyMap()))["mock"])
        assertEquals(1, calls)
        try { tools.call("unknown", JsonObject(emptyMap())); fail("unknown extension name accepted") }
        catch (_: McpTaskTools.ToolError) { assertEquals(1, calls) }
    }

    @Test fun extensionCannotBypassExistingBearerAuthentication() {
        val host = RemoteHost(FakeRemoteTasks(), { "test-only-token" }, 0, false, "test", extension = extension)
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ibot_list_agents","arguments":{}}}""".toByteArray()
        val request = HttpRequest("POST", "/mcp", mapOf("content-type" to "application/json"), body, InetAddress.getByName("127.0.0.1"))
        assertEquals(401, host.handle(request).status)
        assertEquals(0, calls)
    }
}
