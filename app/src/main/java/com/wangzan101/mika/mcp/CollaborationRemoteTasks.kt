package com.wangzan101.mika.mcp

import com.wangzan101.mika.collaboration.CollaborationRun
import com.wangzan101.mika.collaboration.CollaborationRuntime
import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.collaboration.CollaborationStatus
import com.wangzan101.mika.core.remote.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Lets existing query/wait/cancel tools follow collaboration IDs without changing legacy tasks. */
class CollaborationRemoteTasks(private val legacy: RemoteTasks, private val collaboration: CollaborationRuntime) : RemoteTasks {
    override fun start(goal: String, conversation: String?) = legacy.start(goal, conversation)
    override fun get(id: String): RemoteTask? = collaboration.get(id)?.let(::remote) ?: legacy.get(id)
    override fun list(limit: Int): List<RemoteTask> = (legacy.list(limit) + collaboration.runs.value.map(::remote))
        .sortedByDescending { it.createdAtMs }.take(limit)
    override fun answer(id: String, text: String): Boolean = if (collaboration.get(id) != null) false else legacy.answer(id, text)
    override fun cancel(id: String): Boolean {
        val run = collaboration.get(id) ?: return legacy.cancel(id)
        if (run.status !in setOf(CollaborationStatus.Queued, CollaborationStatus.Running)) return false
        collaboration.cancel(id)
        return true
    }
    private fun remote(run: CollaborationRun): RemoteTask {
        val state = when (run.status) {
            CollaborationStatus.Succeeded, CollaborationStatus.PartialSuccess -> TaskState.Succeeded(
                run.result?.let { Json.encodeToString(it) }.orEmpty(), verified = false, reviewed = false,
                note = if (run.status == CollaborationStatus.PartialSuccess) "Partial success; inspect independent agent failures" else "Model opinions are unverified")
            CollaborationStatus.Cancelled -> TaskState.Cancelled
            CollaborationStatus.Failed, CollaborationStatus.Interrupted -> TaskState.Failed(run.error ?: "Collaboration failed or was interrupted", FailKind.Environment)
            CollaborationStatus.HumanTakeover -> TaskState.WaitingTakeover("human_takeover", "Continue on the phone")
            CollaborationStatus.WaitingForConfirmation -> TaskState.WaitingTakeover("confirmation", "Confirm on the phone")
            else -> TaskState.Running(run.events.size, run.events.lastOrNull()?.message ?: "Queued")
        }
        return RemoteTask(run.id, "ibot-${run.id}", run.request.goal, run.startedAtMillis, state)
    }
}
