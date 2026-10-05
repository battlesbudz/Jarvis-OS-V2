package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 6 (schema-review bypass) regression tests: a server's tool-schema
 * change must never silently re-enable a changed tool, and a refresh must
 * never silently clear a pending review.
 */
class McpRegistryTest {

    private class FakeMcpHttp(var toolsJson: String) : McpHttpClient {
        override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse {
            val payload = if (body.contains("tools/list")) toolsJson
            else """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
            return McpHttpResponse(200, payload, emptyMap())
        }
    }

    private fun toolsPayload(vararg tools: Triple<String, String, String>): String {
        // (name, inputSchemaJson, pricing)
        val arr = tools.joinToString(",") { (name, schema, pricing) ->
            """{"name":"$name","description":"d","inputSchema":$schema,"x-jarvis-pricing":"$pricing"}"""
        }
        return """{"jsonrpc":"2.0","id":2,"result":{"tools":[$arr]}}"""
    }

    private val searchV1 = """{"type":"object","properties":{"q":{"type":"string"}}}"""
    private val searchV2 =
        """{"type":"object","properties":{"q":{"type":"string"},"limit":{"type":"integer"}}}"""

    private fun setup(vararg tools: Triple<String, String, String>): Pair<McpRegistry, FakeMcpHttp> {
        val http = FakeMcpHttp(toolsPayload(*tools))
        val flow = McpSetupFlow(http, InMemoryMcpCredentialStore())
        val result = flow.run("Test Server", "https://example.com/mcp", null)
        assertTrue("setup must connect: $result", result is McpSetupFlow.FlowResult.Connected)
        val registry = McpRegistry(http, InMemoryMcpCredentialStore())
        registry.add((result as McpSetupFlow.FlowResult.Connected).status)
        return registry to http
    }

    private fun serverId(registry: McpRegistry) = registry.all().single().config.id

    @Test fun acknowledgeReenablesOnlyUnchangedFreeTools() {
        val (registry, http) = setup(
            Triple("search", searchV1, "free"),
            Triple("summarize", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val id = serverId(registry)
        assertEquals("free tools enabled by default; paid stays disabled",
            setOf("search", "summarize"), registry.status(id)!!.enabledTools)
        // The server changes search's schema and adds a new free tool.
        http.toolsJson = toolsPayload(
            Triple("search", searchV2, "free"),
            Triple("summarize", searchV1, "free"),
            Triple("archive", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, refreshed.state)
        assertTrue(registry.acknowledgeSchemaChange(id))
        val after = registry.status(id)!!
        assertEquals(McpServerState.CONNECTED, after.state)
        assertEquals("only the unchanged free tool stays enabled; the changed " +
            "and the new tool stay disabled until explicitly enabled",
            setOf("summarize"), after.enabledTools)
        // After review the user can explicitly re-enable a changed tool.
        assertTrue(registry.setToolEnabled(id, "search", true))
        assertTrue(registry.status(id)!!.enabledTools.contains("search"))
    }

    @Test fun refreshNeverSilentlyClearsPendingReview() {
        val (registry, http) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        http.toolsJson = toolsPayload(Triple("search", searchV2, "free"))
        assertEquals(McpServerState.SCHEMA_CHANGED, registry.refresh(id).state)
        // A second refresh with the same changed tools must NOT flip back to
        // CONNECTED on its own — the review is still pending.
        val again = registry.refresh(id)
        assertEquals("a refresh must never auto-acknowledge a pending review",
            McpServerState.SCHEMA_CHANGED, again.state)
    }

    @Test fun unchangedRefreshKeepsEnabledTools() {
        val (registry, http) = setup(
            Triple("search", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val id = serverId(registry)
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.CONNECTED, refreshed.state)
        assertEquals(setOf("search"), refreshed.enabledTools)
    }

    @Test fun acknowledgeRequiresPendingReview() {
        val (registry, _) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        assertFalse(registry.acknowledgeSchemaChange(id))
        assertFalse(registry.acknowledgeSchemaChange("no-such-server"))
    }
}
