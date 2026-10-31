package com.wangzan101.mika.collaboration

import com.wangzan101.mika.core.collaboration.*
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class CollaborationSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun phoneToolPolicyRequiresExecutorExplicitPermissionExactToolAndBudget() {
        val offered = setOf("phone_execute_task")
        assertTrue(PhoneToolPolicy.allows(AgentRole.Executor, true, true, offered, "phone_execute_task", true))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Reviewer, true, true, offered, "phone_execute_task", true))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Executor, false, true, offered, "phone_execute_task", true))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Executor, true, false, offered, "phone_execute_task", true))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Executor, true, true, offered, "phone_execute_task", false))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Executor, true, true, offered, "other_tool", true))
        assertFalse(PhoneToolPolicy.allows(AgentRole.Executor, true, true, emptySet(), "phone_execute_task", true))
    }

    @Test fun encryptedPersistenceRoundTripsAndMarksOnlyUnfinishedRunsInterrupted() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = temp.newFile("runs.enc")
        val persistence = EncryptedRunPersistence(file, AesGcmKeyCipher { key })
        val request = CollaborationRequest("sensitive goal text", CollaborationMode.Single, emptyList(), "mock-coordinator")
        val pending = CollaborationRun("pending", request, CollaborationStatus.Running)
        val done = CollaborationRun("done", request, CollaborationStatus.Succeeded)
        persistence.save(listOf(pending, done))
        assertFalse(file.readText().contains("sensitive goal text"))
        val restored = markInterrupted(persistence.load())
        assertEquals(CollaborationStatus.Interrupted, restored.first().status)
        assertEquals(CollaborationStatus.Succeeded, restored.last().status)
    }

    @Test fun terminalRunCannotBeOverwrittenByLateCancelOrCompletion() {
        val run = CollaborationRun("done", CollaborationRequest("x", CollaborationMode.Single, emptyList(), "c"), CollaborationStatus.Cancelled)
        assertSame(run, transitionActive(run) { it.copy(status = CollaborationStatus.Succeeded) })
    }

    @Test fun deterministicMocksProduceRunnableExpertsPlan() = runTest {
        val planner = mockAgentDescriptors().first { it.id == "mock-planner" }
        val answer = DeterministicMockAdapter(planner).complete(AgentCall(AgentRole.Planner, "goal"))
        val steps = Json.parseToJsonElement(answer.text).jsonObject.getValue("steps").jsonArray
        assertEquals(2, steps.size)
        assertEquals("Researcher", steps.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(mockAgentDescriptors().all { it.displayName.contains("(Mock)") && it.connectionKind == ConnectionKind.Mock })
    }

    @Test fun deterministicMockCanRunThroughOrchestrator() = runTest {
        val descriptors = mockAgentDescriptors()
        val adapters = descriptors.map(::DeterministicMockAdapter)
        val coordinator = descriptors.first { it.id == "mock-coordinator" }.id
        val result = CollaborationOrchestrator(adapters).run(
            CollaborationRequest("test goal", CollaborationMode.Single, emptyList(), coordinator, mockOnly = true),
        )
        assertEquals(CollaborationStatus.Succeeded, result.status)
        assertTrue(result.summary.contains("Mock result"))
    }

    @Test fun collaborationConfigsPersistAcrossRestartForThreeProvidersWithoutChangingMainRole() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val dir = temp.newFolder("provider-store")
        val store = ProviderStore(dir, AesGcmKeyCipher { key })
        val caps = Capabilities(toolCalls = false, vision = false, contextTokens = 4096, reasoning = false)
        val configs = listOf("Alpha", "Beta", "Gamma").mapIndexed { index, name ->
            val provider = store.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://p$index.test/v1", "key-$index",
                listOf(ListedModel("model-$index", chat = true)))
            store.ensureCollaborationConfig(provider.id, "model-$index", caps)!!
        }
        assertNull(store.roles.value.main)
        assertEquals(3, configs.map { it.providerId }.distinct().size)

        val restored = ProviderStore(dir, AesGcmKeyCipher { key })
        assertEquals(setOf("model-0", "model-1", "model-2"), restored.list().map { it.model }.toSet())
        assertNull(restored.roles.value.main)
        assertEquals(3, restored.providers.value.size)
    }

    @Test fun ordinaryUnregisteredConfigStillPrunesAfterRestart() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val dir = temp.newFolder("ordinary-provider-store")
        val store = ProviderStore(dir, AesGcmKeyCipher { key })
        val caps = Capabilities(toolCalls = false, vision = false, contextTokens = 4096, reasoning = false)
        val provider = store.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://unused.test/v1", "key", emptyList())
        store.ensureCollaborationConfig(provider.id, "retained", caps)
        store.save(ProviderConfig("unreferenced", ProviderConfig.Kind.OpenAiCompat, provider.baseUrl, "unused", caps, provider.name, provider.id), null)
        val restored = ProviderStore(dir, AesGcmKeyCipher { key })
        assertEquals(listOf("retained"), restored.list().map { it.model })
    }
}
