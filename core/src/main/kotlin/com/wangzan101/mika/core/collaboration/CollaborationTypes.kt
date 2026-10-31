package com.wangzan101.mika.core.collaboration

import kotlinx.serialization.Serializable

@Serializable enum class CollaborationMode { Single, Sequential, Parallel, Experts }
@Serializable enum class AgentRole { Planner, Researcher, Executor, Reviewer, Coordinator }
@Serializable enum class ConnectionKind { Model, RemoteAgent, ExistingApp, Mock }
@Serializable enum class CollaborationStatus { Queued, Running, WaitingForConfirmation, HumanTakeover, Succeeded, PartialSuccess, Failed, Cancelled, Interrupted }

@Serializable data class AgentDescriptor(
    val id: String,
    val displayName: String,
    val provider: String,
    val model: String,
    val configured: Boolean,
    val connectionKind: ConnectionKind = ConnectionKind.Model,
    val inputMicrosPerMillionTokens: Long? = null,
    val outputMicrosPerMillionTokens: Long? = null,
    val enabled: Boolean = true,
)

@Serializable data class RoleAssignment(val role: AgentRole, val agentId: String, val fallbackAgentId: String? = null)

@Serializable data class TaskLimits(
    val maxRequests: Int = 20,
    val maxConcurrent: Int = 2,
    val requestTimeoutMillis: Long = 120000,
    val taskTimeoutMillis: Long = 600000,
    val maxOutputTokens: Int = 2048,
    val maxInputBytes: Int = 32000,
    val maxTokens: Int = 32000,
    val maxCostMicros: Long = 0,
)

@Serializable data class CollaborationRequest(
    val goal: String,
    val mode: CollaborationMode,
    val assignments: List<RoleAssignment>,
    val coordinatorId: String,
    val limits: TaskLimits = TaskLimits(),
    val mockOnly: Boolean = true,
    val allowPhoneExecution: Boolean = false,
)

@Serializable data class AgentCall(
    val role: AgentRole,
    val goal: String,
    val context: List<AgentOutput> = emptyList(),
    val maxOutputTokens: Int = 2048,
    val requestId: String = "",
    val allowPhoneExecution: Boolean = false,
    @kotlinx.serialization.Transient val additionalRequestBudget: AdditionalRequestBudget? = null,
)

interface AdditionalRequestBudget {
    fun reserve(inputBytes: Int, maxOutputTokens: Int)
}

@Serializable data class AgentOutput(
    val agentId: String,
    val role: AgentRole,
    val text: String,
    val verified: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val needsHuman: Boolean = false,
)

@Serializable data class CollaborationEvent(
    val sequence: Int,
    val kind: String,
    val agentId: String? = null,
    val role: AgentRole? = null,
    val message: String,
)

@Serializable data class CollaborationResult(
    val status: CollaborationStatus,
    val outputs: List<AgentOutput>,
    val summary: String,
    val events: List<CollaborationEvent>,
    val requests: Int,
    val reservedTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
)

interface AgentAdapter {
    val descriptor: AgentDescriptor
    suspend fun complete(call: AgentCall): AgentOutput
}
