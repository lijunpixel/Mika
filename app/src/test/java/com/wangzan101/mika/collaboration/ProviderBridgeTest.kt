package com.wangzan101.mika.collaboration

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.settings.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ProviderBridgeTest {
    @get:Rule val temp = TemporaryFolder()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun explicitlyAssignedConfigSurvivesRolePruningAndUsesMeteredFactory() = runTest {
        val usages = mutableListOf<UsageSource>()
        val store = ProviderStore(temp.root, AesGcmKeyCipher { aes }, usage = { source, _ -> usages += source },
            modelFactory = { cfg, key, _, setup ->
                assertEquals("secret", key)
                assertEquals(123, setup.maxOutputTokens)
                StubModel(cfg.capabilities)
            })
        val caps = Capabilities(toolCalls = false, vision = false, contextTokens = 4096, reasoning = false)
        val a = ProviderConfig("a", ProviderConfig.Kind.OpenAiCompat, "https://a.test/v1", "a-model", caps)
        val b = ProviderConfig("b", ProviderConfig.Kind.OpenAiCompat, "https://b.test/v1", "b-model", caps)
        store.save(a, null); store.save(b, "secret")
        store.retainCollaborationConfigs(setOf("b"))
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("a")))
        val collaborationModel = store.modelForConfig("b", maxOutputTokens = 123)
        assertNotNull("collaboration reference must survive prune", collaborationModel)
        collaborationModel!!.complete("system", emptyList(), emptyList())
        assertTrue(store.list().any { it.id == "b" })
        assertNull(store.modelForConfig("missing"))
        val cfg = store.list().first { it.id == "b" }
        assertEquals(UsageSource(cfg.providerId, store.provider(cfg.providerId)!!.name, "b-model", UsagePurpose.Other), usages.first())
    }

    private class StubModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(1, 1))
    }
}
