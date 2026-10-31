package com.wangzan101.mika.core.collaboration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class TransientAgentException(message: String, cause: Throwable? = null) : Exception(message, cause)
private class BudgetExceededException(message: String) : Exception(message)

class CollaborationOrchestrator(adapters: List<AgentAdapter>) {
    private val adapters = adapters.associateBy { it.descriptor.id }

    suspend fun run(request: CollaborationRequest, onEvent: (CollaborationEvent) -> Unit = {}): CollaborationResult {
        val events = mutableListOf<CollaborationEvent>()
        val outputs = mutableListOf<AgentOutput>()
        val failureNotes = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun failureContext(): String = synchronized(failureNotes) { failureNotes.joinToString("\n") }
        val sequence = AtomicInteger()
        fun event(kind: String, message: String, id: String? = null, role: AgentRole? = null) {
            val value = synchronized(events) {
                CollaborationEvent(sequence.incrementAndGet(), kind, id, role, message).also { events += it }
            }
            onEvent(value)
        }
        val failureCount = AtomicInteger()
        val humanRequired = java.util.concurrent.atomic.AtomicBoolean()
        val limits = request.limits
        if (limits.maxRequests <= 0 || limits.maxConcurrent <= 0 || limits.requestTimeoutMillis <= 0 || limits.taskTimeoutMillis <= 0 ||
            limits.maxRequests > 100 || limits.maxConcurrent > 8 || limits.maxInputBytes <= 0 || limits.maxInputBytes > 128000 ||
            limits.maxOutputTokens <= 0 || limits.maxOutputTokens > 8192 || limits.requestTimeoutMillis > 3600000 ||
            limits.taskTimeoutMillis > 3600000 || limits.maxTokens < 0 || limits.maxCostMicros < 0) {
            event("failed", "invalid task limits or configured limit exceeds safety cap")
            return CollaborationResult(CollaborationStatus.Failed, emptyList(), "invalid task limits", events.toList(), 0)
        }
        if (request.goal.isBlank()) {
            event("failed", "goal must not be blank")
            return CollaborationResult(CollaborationStatus.Failed, emptyList(), "goal must not be blank", events.toList(), 0)
        }
        if (request.assignments.size > 5 || request.assignments.map { it.role }.distinct().size != request.assignments.size) {
            event("failed", "assignments must contain at most five unique roles")
            return CollaborationResult(CollaborationStatus.Failed, emptyList(), "invalid role assignments", events.toList(), 0)
        }
        if (request.goal.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes) {
            event("failed", "goal exceeds maxInputBytes")
            return CollaborationResult(CollaborationStatus.Failed, emptyList(), "", events.toList(), 0)
        }

        val requests = AtomicInteger()
        val tokens = AtomicLong()
        val reservedCost = AtomicLong()
        val semaphore = Semaphore(limits.maxConcurrent)
        val runDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.taskTimeoutMillis)

        fun reserveCost(amount: Long): Boolean {
            while (true) {
                val current = reservedCost.get()
                if (amount > limits.maxCostMicros - current) return false
                if (reservedCost.compareAndSet(current, current + amount)) return true
            }
        }
        fun adjustCost(delta: Long) {
            while (true) {
                val current = reservedCost.get()
                val next = if (delta >= 0) {
                    if (delta > Long.MAX_VALUE - current) Long.MAX_VALUE else current + delta
                } else (current + delta).coerceAtLeast(0L)
                if (reservedCost.compareAndSet(current, next)) return
            }
        }

        fun configured(id: String, role: AgentRole): AgentAdapter {
            val adapter = adapters[id] ?: error("unknown agent: $id")
            require(adapter.descriptor.configured) { "agent is not configured: $id" }
            require(adapter.descriptor.enabled) { "agent is disabled: $id" }
            require(adapter.descriptor.connectionKind != ConnectionKind.ExistingApp) { "ExistingApp adapter has no completion API: $id" }
            require(!request.mockOnly || adapter.descriptor.connectionKind == ConnectionKind.Mock) { "mockOnly forbids real adapter: $id" }
            if (adapter.descriptor.connectionKind != ConnectionKind.Mock) {
                require(limits.maxCostMicros > 0) { "zero cost budget forbids real adapter: $id" }
                require(adapter.descriptor.inputMicrosPerMillionTokens?.let { it >= 0 } == true && adapter.descriptor.outputMicrosPerMillionTokens?.let { it >= 0 } == true) {
                    "real adapter pricing is unknown; refusing call"
                }
            }
            return adapter
        }

        fun reserveRequest(inputBytes: Int, outputTokens: Int, descriptor: AgentDescriptor): Pair<Int, Long> {
            require(inputBytes in 0..limits.maxInputBytes) { "nested request exceeds maxInputBytes" }
            require(outputTokens in 1..limits.maxOutputTokens) { "nested request exceeds maxOutputTokens" }
            var count: Int
            while (true) {
                val current = requests.get()
                if (current >= limits.maxRequests) throw BudgetExceededException("request budget exceeded")
                if (requests.compareAndSet(current, current + 1)) {
                    count = current + 1
                    break
                }
            }
            val inputReservation = inputBytes.toLong() + 1024L
            val tokenReservation = inputReservation + outputTokens
            if (tokens.addAndGet(tokenReservation) > limits.maxTokens.toLong()) {
                tokens.addAndGet(-tokenReservation)
                throw BudgetExceededException("token budget exceeded")
            }
            val costReservation = if (descriptor.connectionKind == ConnectionKind.Mock) 0L else estimateCost(
                inputReservation, outputTokens.toLong(), descriptor.inputMicrosPerMillionTokens!!, descriptor.outputMicrosPerMillionTokens!!,
            )
            if (!reserveCost(costReservation)) {
                tokens.addAndGet(-tokenReservation)
                throw BudgetExceededException("cost budget exceeded")
            }
            return count to tokenReservation
        }

        suspend fun invoke(role: AgentRole, id: String, goal: String, context: List<AgentOutput> = emptyList(), fallback: String? = null): AgentOutput {
            check(!humanRequired.get()) { "human takeover required" }
            if (goal.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes) {
                event("failed", "request goal exceeds maxInputBytes", id, role)
                throw IllegalArgumentException("request goal exceeds maxInputBytes")
            }
            val contextBytes = context.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() }
            val goalBytes = goal.toByteArray(Charsets.UTF_8).size.toLong()
            if (contextBytes + goalBytes > limits.maxInputBytes) {
                event("failed", "request context exceeds maxInputBytes", id, role)
                throw IllegalArgumentException("request context exceeds maxInputBytes")
            }
            val agent = try { configured(id, role) } catch (cancel: CancellationException) { throw cancel } catch (t: Throwable) {
                event("failed", safeFailure(t), id, role)
                throw t
            }
            val outputCap = limits.maxOutputTokens
            val inputBytes = (goalBytes + contextBytes).toInt()
            suspend fun attempt(target: AgentAdapter): AgentOutput {
                semaphore.withPermit {
                    val price = target.descriptor
                    try {
                        val (count, reservation) = reserveRequest(inputBytes, outputCap, target.descriptor)
                        val inputReservation = inputBytes.toLong() + 1024L
                        val costReservation = if (price.connectionKind == ConnectionKind.Mock) 0L else estimateCost(
                            inputReservation, outputCap.toLong(), price.inputMicrosPerMillionTokens!!, price.outputMicrosPerMillionTokens!!,
                        )
                        val timeLeft = ((runDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                        event("started", "agent call started", target.descriptor.id, role)
                        val raw = try {
                            withTimeout(minOf(limits.requestTimeoutMillis, timeLeft)) {
                                target.complete(AgentCall(
                                    role, goal, context, outputCap, "collab-$count", role == AgentRole.Executor && request.allowPhoneExecution,
                                    object : AdditionalRequestBudget {
                                        override fun reserve(inputBytes: Int, maxOutputTokens: Int) {
                                            reserveRequest(inputBytes, maxOutputTokens, target.descriptor)
                                        }
                                    },
                                ))
                            }
                        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            throw java.util.concurrent.TimeoutException("agent request timed out")
                        }
                        require((raw.inputTokens == null || raw.inputTokens >= 0) && (raw.outputTokens == null || raw.outputTokens >= 0)) { "adapter reported negative token usage" }
                        val used = (raw.inputTokens?.toLong() ?: inputReservation) + (raw.outputTokens?.toLong() ?: outputCap.toLong())
                        var exceededBudget = false
                        if (used > reservation) {
                            val delta = used - reservation
                            tokens.addAndGet(delta)
                            exceededBudget = tokens.get() > limits.maxTokens.toLong()
                        } else tokens.addAndGet(used - reservation)
                        if (price.connectionKind != ConnectionKind.Mock && raw.inputTokens != null && raw.outputTokens != null) {
                            val actualCost = estimateCost(raw.inputTokens.toLong(), raw.outputTokens.toLong(), price.inputMicrosPerMillionTokens!!, price.outputMicrosPerMillionTokens!!)
                            val delta = actualCost - costReservation
                            adjustCost(delta)
                            exceededBudget = exceededBudget || reservedCost.get() > limits.maxCostMicros
                        }
                        if (exceededBudget) throw BudgetExceededException("actual usage exceeded reserved budget")
                        if (raw.needsHuman) humanRequired.set(true)
                        event("completed", "agent call completed", target.descriptor.id, role)
                        val source = "Source: ${price.displayName} (${price.provider}/${price.model}, id=${price.id})\n${raw.text}"
                        event("output", source.take(12000), target.descriptor.id, role)
                        event("handoff", "agent output returned to orchestrator", target.descriptor.id, role)
                        return raw.copy(agentId = target.descriptor.id, role = role, verified = false)
                    } catch (cancel: CancellationException) {
                        event("interrupted", "agent call cancelled; reservation retained", target.descriptor.id, role)
                        throw cancel
                    } catch (t: Throwable) {
                        event("failed", "${safeFailure(t)}; reservation retained", target.descriptor.id, role)
                        throw t
                    }
                }
            }
            return try {
                try { attempt(agent) } catch (t: TransientAgentException) {
                    if (role == AgentRole.Executor) throw t
                    event("retry", "transient failure; retrying once", id, role)
                    attempt(agent)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                if (fallback != null && fallback != id && role != AgentRole.Executor && t !is BudgetExceededException) {
                    event("fallback", "primary failed; using configured fallback", fallback, role)
                    val backup = try { configured(fallback, role) } catch (cancel: CancellationException) { throw cancel } catch (fallbackError: Throwable) {
                        event("failed", safeFailure(fallbackError), fallback, role)
                        throw fallbackError
                    }
                    try { attempt(backup) } catch (cancel: CancellationException) { throw cancel }
                } else throw t
            }
        }

        fun assignment(role: AgentRole): RoleAssignment? = request.assignments.firstOrNull { it.role == role }
        event("started", "collaboration started")
        try {
            val completedBeforeTimeout = withTimeoutOrNull(limits.taskTimeoutMillis) {
                when (request.mode) {
                    CollaborationMode.Single -> outputs += invoke(AgentRole.Coordinator, request.coordinatorId, request.goal, fallback = assignment(AgentRole.Coordinator)?.fallbackAgentId)
                    CollaborationMode.Sequential -> {
                        for (a in request.assignments.filter { it.role != AgentRole.Coordinator }) {
                            try { outputs += invoke(a.role, a.agentId, request.goal, outputs.takeLast(1), a.fallbackAgentId) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (t: Throwable) {
                                failureCount.incrementAndGet()
                                failureNotes += "${a.role} (${a.agentId}): ${safeFailure(t)}"
                                event("failed", safeFailure(t), a.agentId, a.role)
                            }
                        }
                        outputs += invoke(AgentRole.Coordinator, request.coordinatorId, "Original goal: ${request.goal}\nFailures:\n${failureContext()}\nSummarize results, identify disagreement, and state uncertainty.", outputs.toList(), assignment(AgentRole.Coordinator)?.fallbackAgentId)
                    }
                    CollaborationMode.Parallel -> {
                        val answers = coroutineScope {
                            request.assignments.filter { it.role != AgentRole.Coordinator }.map { a -> async {
                                try { invoke(a.role, a.agentId, request.goal, fallback = a.fallbackAgentId) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (t: Throwable) {
                                    failureCount.incrementAndGet()
                                    failureNotes += "${a.role} (${a.agentId}): ${safeFailure(t)}"
                                    event("failed", safeFailure(t), a.agentId, a.role)
                                    null
                                }
                            } }.awaitAll().filterNotNull()
                        }
                        outputs += answers
                        outputs += invoke(AgentRole.Coordinator, request.coordinatorId, "Original goal: ${request.goal}\nFailures:\n${failureContext()}\nSynthesize these independent answers; identify agreement, disagreement, and uncertainty.", answers, assignment(AgentRole.Coordinator)?.fallbackAgentId)
                    }
                    CollaborationMode.Experts -> {
                        val planner = assignment(AgentRole.Planner) ?: error("Experts mode requires Planner assignment")
                        val planOut = invoke(AgentRole.Planner, planner.agentId,
                            "Original goal: ${request.goal}\nReturn only JSON {\"steps\":[{\"id\":string,\"role\":one of Researcher,Executor,Reviewer,\"task\":string,\"dependsOn\":[string]}]}. At most 8 steps; no hidden reasoning.", fallback = planner.fallbackAgentId)
                        outputs += planOut
                        val steps = validatePlan(planOut.text, request.assignments.map { it.role }.toSet())
                        for (step in steps) {
                            val stepAssignment = assignment(step.role) ?: error("expert role assignment is missing")
                            configured(stepAssignment.agentId, step.role)
                            stepAssignment.fallbackAgentId?.let { configured(it, step.role) }
                        }
                        val completed = linkedMapOf<String, AgentOutput>()
                        val failedSteps = mutableSetOf<String>()
                        val pending = steps.toMutableList()
                        while (pending.isNotEmpty()) {
                            val ready = pending.filter { step -> step.dependsOn.all { completed.containsKey(it) || it in failedSteps } }
                            if (ready.isEmpty()) error("expert plan has cycle or unresolved dependency")
                            for (step in ready) {
                                if (step.dependsOn.any { it in failedSteps }) {
                                    failedSteps += step.id
                                    failureCount.incrementAndGet()
                                    failureNotes += "${step.role}: dependent step failed"
                                    event("skipped", "step depends on a failed step", role = step.role)
                                    pending.remove(step)
                                    continue
                                }
                                val a = assignment(step.role) ?: error("no assignment for role ${step.role}")
                                val visibleContext = step.dependsOn.map { completed.getValue(it) }
                                try {
                                    val result = invoke(step.role, a.agentId, "Original goal: ${request.goal}\nTask: ${step.task}", visibleContext, a.fallbackAgentId)
                                    completed[step.id] = result
                                    outputs += result
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (t: Throwable) {
                                    failedSteps += step.id
                                    failureCount.incrementAndGet()
                                    failureNotes += "${step.role} (${a.agentId}): ${safeFailure(t)}"
                                    event("failed", safeFailure(t), a.agentId, step.role)
                                }
                                pending.remove(step)
                            }
                        }
                        outputs += invoke(AgentRole.Coordinator, request.coordinatorId, "Original goal: ${request.goal}\nFailures:\n${failureContext()}\nSynthesize expert results and failures.", completed.values.toList(), assignment(AgentRole.Coordinator)?.fallbackAgentId)
                    }
                }
                true
            }
            if (completedBeforeTimeout == null) {
                failureCount.incrementAndGet()
                failureNotes += "collaboration task timeout"
                event("timeout", "collaboration timed out")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            failureCount.incrementAndGet(); failureNotes += safeFailure(t); event("failed", safeFailure(t))
        }
        val status = when {
            humanRequired.get() -> CollaborationStatus.HumanTakeover
            failureCount.get() > 0 && outputs.isNotEmpty() -> CollaborationStatus.PartialSuccess
            failureCount.get() > 0 -> CollaborationStatus.Failed
            else -> CollaborationStatus.Succeeded
        }
        event("finished", status.name)
        return CollaborationResult(status, outputs.toList(), outputs.lastOrNull()?.text.orEmpty(), events.toList(), requests.get(), tokens.get(), reservedCost.get())
    }

    private data class PlanStep(val id: String, val role: AgentRole, val task: String, val dependsOn: List<String>)

    private fun safeFailure(error: Throwable): String = when (error) {
        is BudgetExceededException -> "budget limit exceeded"
        is java.util.concurrent.TimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "request timed out"
        is TransientAgentException -> "temporary agent failure"
        is IllegalArgumentException -> "input rejected"
        else -> "agent operation failed"
    }

    private fun estimateCost(inputTokens: Long, outputTokens: Long, inputRate: Long, outputRate: Long): Long {
        val million = java.math.BigInteger.valueOf(1_000_000)
        val micros = java.math.BigInteger.valueOf(inputTokens).multiply(java.math.BigInteger.valueOf(inputRate))
            .add(java.math.BigInteger.valueOf(outputTokens).multiply(java.math.BigInteger.valueOf(outputRate)))
            .add(million.subtract(java.math.BigInteger.ONE)).divide(million)
        return micros.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }

    private fun validatePlan(text: String, assignedRoles: Set<AgentRole>): List<PlanStep> {
        val root = Json.parseToJsonElement(text).jsonObject
        val steps = root.getValue("steps").jsonArray.map { value ->
            val obj = value.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            require(id.isNotBlank()) { "expert plan step id is blank" }
            val roleText = obj.getValue("role").jsonPrimitive.content
            val role = AgentRole.entries.firstOrNull { it.name == roleText } ?: error("unknown expert role: $roleText")
            require(role in setOf(AgentRole.Researcher, AgentRole.Executor, AgentRole.Reviewer)) { "invalid expert role: $role" }
            require(role in assignedRoles) { "expert node role is not assigned" }
            val task = obj.getValue("task").jsonPrimitive.content
            require(task.isNotBlank()) { "expert task is blank" }
            PlanStep(id, role, obj.getValue("task").jsonPrimitive.content,
                obj["dependsOn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
        }
        require(steps.size <= 8) { "expert plan exceeds 8 steps" }
        require(steps.map { it.id }.distinct().size == steps.size) { "duplicate expert step id" }
        val ids = steps.map { it.id }.toSet()
        require(steps.all { it.dependsOn.all(ids::contains) }) { "expert plan has missing dependency" }
        val ordered = mutableListOf<PlanStep>()
        val pending = steps.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { step -> step.dependsOn.all { dependency -> ordered.any { it.id == dependency } } }
            require(ready.isNotEmpty()) { "expert plan contains a cycle" }
            ordered += ready
            pending.removeAll(ready.toSet())
        }
        return ordered
    }
}
