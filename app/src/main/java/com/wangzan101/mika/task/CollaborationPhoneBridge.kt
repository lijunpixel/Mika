package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.collaboration.PhoneExecutionBridge
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ChatModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Runs a collaborator's phone request in Mika's existing TaskService with an isolated, budgeted model. */
class CollaborationPhoneBridge(context: Context) : PhoneExecutionBridge {
    private val app = context.applicationContext as MikaApp
    private val env = TaskEnvironment.of(app)

    override suspend fun execute(task: String, executorConfigId: String, model: ChatModel): String =
        globalPhoneRun.withLock {
            if (task.isBlank() || executorConfigId.isBlank()) return@withLock "MANUAL_REQUIRED: phone task or executor identity is missing."
            val conversationId = UUID.randomUUID().toString()
            val override = try {
                env.installCollaboration(conversationId, model, PHONE_TOOL_ALLOWLIST)
                env.collaboration(conversationId)!!
            } catch (_: Exception) {
                return@withLock "MANUAL_REQUIRED: isolated phone model could not be installed."
            }
            val active = Active(conversationId, null)
            activeRuns[executorConfigId] = active
            var started = false
            try {
                TaskService.startCollaboration(app, task.trim(), conversationId)
                started = true
                val terminal = withTimeout(RUN_TIMEOUT_MS) {
                    coroutineScope {
                        val lockedDeviceMonitor = launch {
                            val run = app.taskState.runs.first { runs -> runs.any { it.conversationId == conversationId } }
                                .first { it.conversationId == conversationId }
                            run.state.first { it is TaskState.Held && it.reason.needsYou }
                            TaskService.cancel(app, run.key)
                        }
                        try { override.result.await() } finally { lockedDeviceMonitor.cancel() }
                    }
                }
                describe(terminal)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                "MANUAL_REQUIRED: phone executor exceeded its bounded run window; it was stopped."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "MANUAL_REQUIRED: phone executor could not complete safely."
            } finally {
                withContext(NonCancellable) {
                    if (started && !override.result.isCompleted) {
                        cancelRun(conversationId)
                        withTimeoutOrNull(CANCEL_SETTLE_MS) { override.result.await() }
                    }
                    activeRuns.remove(executorConfigId, active)
                    // A delayed START_COLLAB is rejected by TaskService if this lease has been removed.
                    env.removeCollaboration(conversationId)
                }
            }
        }

    /** A takeover request is tied to this bridge's active collaboration run only. */
    override fun takeOver(executorConfigId: String) {
        val active = activeRuns[executorConfigId] ?: return
        val run = app.taskState.runs.value.firstOrNull { it.conversationId == active.conversationId } ?: return
        active.runKey = run.key
        TaskService.collaborationTakeOver(app, run.key)
    }

    private suspend fun cancelRun(conversationId: String) {
        val run = withTimeoutOrNull(START_SETTLE_MS) {
            app.taskState.runs.first { runs -> runs.any { it.conversationId == conversationId } }
                .first { it.conversationId == conversationId }
        }
        run?.let { TaskService.cancel(app, run.key) }
    }

    private fun describe(state: TaskState): String = when (state) {
        is TaskState.Succeeded -> buildString {
            if (state.doubtful) append("UNVERIFIED: ").append(state.note ?: "result could not be verified").append("\n")
            append(state.answer.ifBlank { "Completed without a textual result." })
            if (state.unconfirmed.isNotEmpty()) append("\nUnconfirmed: ").append(state.unconfirmed.joinToString("; "))
        }
        is TaskState.Failed -> when (state.kind) {
            com.wangzan101.mika.core.agent.FailKind.Environment -> "MANUAL_REQUIRED: phone runtime setup or device access needs attention."
            com.wangzan101.mika.core.agent.FailKind.Guard -> "MANUAL_REQUIRED: a safety guard stopped the phone action."
            com.wangzan101.mika.core.agent.FailKind.GaveUp -> "FAILED: the executor could not complete the requested task."
            com.wangzan101.mika.core.agent.FailKind.Model -> "FAILED: the executor model request failed."
            com.wangzan101.mika.core.agent.FailKind.StepLimit -> "FAILED: the executor step limit was reached."
        }
        TaskState.Cancelled -> "MANUAL_REQUIRED: the phone task was canceled."
        else -> "MANUAL_REQUIRED: phone task ended in an unexpected non-terminal state."
    }

    private data class Active(val conversationId: String, @Volatile var runKey: String?)

    private companion object {
        val globalPhoneRun = Mutex()
        val activeRuns = ConcurrentHashMap<String, Active>()
        const val RUN_TIMEOUT_MS = 15 * 60_000L
        const val CANCEL_SETTLE_MS = 30_000L
        const val START_SETTLE_MS = 3_000L

        // Verified from PhoneGroup and the general OutcomeTools/Wait declarations. Composite phone_run
        // dispatches each internal phone action through the filtered registry, so the same boundary
        // applies to its substeps and to do_steps.
        val PHONE_TOOL_ALLOWLIST = setOf(
            "phone_observe", "phone_list_elements", "phone_tap", "phone_scroll", "phone_swipe",
            "phone_type_text", "phone_clear_text", "phone_back", "phone_press_key", "phone_diagnose",
            "phone_query", "phone_find", "phone_extract", "phone_collect", "phone_wait", "phone_event",
            "phone_dialog", "phone_gesture", "phone_run", "phone_list_apps", "phone_launch_app",
            "ask_user", "request_confirmation", "hand_to_user", "end_not_done", "wait",
        )
    }
}
