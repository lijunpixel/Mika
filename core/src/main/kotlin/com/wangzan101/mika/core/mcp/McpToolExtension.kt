package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonObject

/** Optional application tools, behind the same MCP authentication boundary. */
interface McpToolExtension {
    val tools: List<McpTaskTools.Tool>
    suspend fun call(name: String, args: JsonObject): JsonObject
}
