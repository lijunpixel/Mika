package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollaborationOverrideTest {
    @Test fun overrideIsConversationScopedAndCompletesBeforeRemoval() = runTest {
        val env = TaskEnvironment()
        val model = FakeWrappedModel()
        val allowed = setOf("phone_observe", "hand_to_user")
        env.installCollaboration("isolated-thread", model, allowed)

        val lease = env.collaboration("isolated-thread")!!
        assertSame(model, lease.model)
        assertEquals(allowed, lease.toolAllowlist)
        assertNull(env.collaboration("another-thread"))
        assertTrue(!lease.result.isCompleted)

        val terminal = TaskState.Succeeded("phone result")
        env.completeCollaboration("isolated-thread", terminal)
        assertEquals(terminal, lease.result.await())
        env.removeCollaboration("isolated-thread")
        assertNull(env.collaboration("isolated-thread"))
        assertEquals(terminal, lease.result.await())
    }

    private class FakeWrappedModel : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 1_000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(listOf(Part.Text("unused")), StopReason.EndTurn, null)
    }
}
