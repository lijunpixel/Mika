package com.wangzan101.mika.core.collaboration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CollaborationOrchestratorTest {
    private class Fake(
        override val descriptor: AgentDescriptor,
        private val handler: suspend (AgentCall) -> AgentOutput = { call -> AgentOutput(descriptor.id, call.role, "${descriptor.id}:${call.goal}") },
    ) : AgentAdapter {
        val calls = mutableListOf<AgentCall>()
        override suspend fun complete(call: AgentCall): AgentOutput {
            synchronized(calls) { calls += call }
            return handler(call)
        }
    }

    private fun mock(id: String, handler: suspend (AgentCall) -> AgentOutput = { call -> AgentOutput(id, call.role, "$id answer") }) =
        Fake(AgentDescriptor(id, id, "test", "fake", true, ConnectionKind.Mock), handler)

    private fun request(mode: CollaborationMode, assignments: List<RoleAssignment>, limits: TaskLimits = TaskLimits(), goal: String = "goal") =
        CollaborationRequest(goal, mode, assignments, "coord", limits)

    @Test fun manualTakeoverStopsLaterRolesAndCoordinator() = runTest {
        val coord = mock("coord")
        val executor = mock("executor") { call -> AgentOutput("executor", call.role, "Unlock manually", needsHuman = true) }
        val reviewer = mock("reviewer")
        val result = CollaborationOrchestrator(listOf(coord, executor, reviewer)).run(request(CollaborationMode.Sequential,
            listOf(RoleAssignment(AgentRole.Executor, "executor"), RoleAssignment(AgentRole.Reviewer, "reviewer"))))
        assertEquals(CollaborationStatus.HumanTakeover, result.status)
        assertEquals(1, result.outputs.size)
        assertTrue(coord.calls.isEmpty())
        assertTrue(reviewer.calls.isEmpty())
    }

    @Test fun disabledAgentAndExecutorFallbackCannotBypassAuthorization() = runTest {
        val coord = mock("coord")
        val disabled = Fake(AgentDescriptor("disabled", "disabled", "test", "fake", true, ConnectionKind.Mock, enabled = false))
        val blocked = CollaborationOrchestrator(listOf(coord, disabled)).run(request(CollaborationMode.Single, emptyList()).copy(coordinatorId = "disabled"))
        assertEquals(CollaborationStatus.Failed, blocked.status)
        assertTrue(disabled.calls.isEmpty())
        val executor = mock("executor") { throw TransientAgentException("temporary") }
        val backup = mock("backup")
        CollaborationOrchestrator(listOf(coord, executor, backup)).run(request(CollaborationMode.Sequential,
            listOf(RoleAssignment(AgentRole.Executor, "executor", "backup"))))
        assertEquals(1, executor.calls.size)
        assertTrue(backup.calls.isEmpty())
    }

    @Test fun singleAndSequentialDispatchCorrectlyAndMarkUnverified() = runTest {
        val coord = mock("coord")
        val one = mock("one")
        val two = mock("two")
        val orch = CollaborationOrchestrator(listOf(coord, one, two))
        val single = orch.run(request(CollaborationMode.Single, emptyList()))
        assertEquals("coord answer", single.summary)
        assertFalse(single.outputs.single().verified)
        assertTrue(single.events.any { it.kind == "output" && it.message.startsWith("Source: coord (test/fake, id=coord)") })
        assertTrue(single.reservedTokens > 0)
        assertEquals(0L, single.estimatedCostMicros)
        val seq = orch.run(request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Researcher, "one"), RoleAssignment(AgentRole.Reviewer, "two"))))
        assertEquals(listOf("one", "two"), seq.outputs.take(2).map { it.agentId })
        assertEquals(listOf("one answer"), two.calls.single().context.map { it.text })
    }

    @Test fun parallelIsBoundedAndDoesNotSharePeerAnswers() = runTest {
        var active = 0
        var peak = 0
        val agents = (1..4).map { n -> mock("a$n") { call ->
            synchronized(this) { active++; peak = maxOf(peak, active) }
            delay(20)
            synchronized(this) { active-- }
            AgentOutput("", call.role, "answer$n")
        } }
        val coord = mock("coord")
        val result = CollaborationOrchestrator(agents + coord).run(
            request(CollaborationMode.Parallel, agents.mapIndexed { i, a -> RoleAssignment(listOf(AgentRole.Researcher, AgentRole.Executor, AgentRole.Reviewer, AgentRole.Planner)[i], a.descriptor.id) }, TaskLimits(maxConcurrent = 2)),
        )
        assertTrue(peak <= 2)
        assertTrue(agents.all { it.calls.single().context.isEmpty() })
        assertEquals(CollaborationStatus.Succeeded, result.status)
    }

    @Test fun parallelFailureProducesPartialResultButKeepsOtherAnswers() = runTest {
        val bad = mock("bad") { throw IllegalStateException("offline") }
        val good = mock("good")
        val coord = mock("coord")
        val result = CollaborationOrchestrator(listOf(coord, bad, good)).run(
            request(CollaborationMode.Parallel, listOf(RoleAssignment(AgentRole.Researcher, "bad"), RoleAssignment(AgentRole.Reviewer, "good"))),
        )
        assertEquals(CollaborationStatus.PartialSuccess, result.status)
        assertTrue(result.outputs.any { it.agentId == "good" })
        assertTrue(result.events.any { it.kind == "failed" })
        assertFalse(coord.calls.single().goal.contains("offline"))
        assertFalse(result.events.any { it.message.contains("offline") })
    }

    @Test fun parallelRequestTimeoutDoesNotCancelHealthyPeersOrSummary() = runTest {
        val slow = mock("slow") { delay(100); AgentOutput("slow", it.role, "late") }
        val fast = mock("fast")
        val coord = mock("coord")
        val result = CollaborationOrchestrator(listOf(coord, slow, fast)).run(
            request(CollaborationMode.Parallel, listOf(RoleAssignment(AgentRole.Researcher, "slow"), RoleAssignment(AgentRole.Reviewer, "fast")), TaskLimits(requestTimeoutMillis = 20, taskTimeoutMillis = 500)),
        )
        assertEquals(CollaborationStatus.PartialSuccess, result.status)
        assertTrue(result.outputs.any { it.agentId == "fast" })
        assertEquals(1, coord.calls.size)
    }

    @Test fun parallelRequestReservationsShareGlobalRequestBudget() = runTest {
        val agents = (1..4).map { mock("a$it") }
        val coord = mock("coord")
        val result = CollaborationOrchestrator(agents + coord).run(
            request(CollaborationMode.Parallel, agents.mapIndexed { i, a -> RoleAssignment(listOf(AgentRole.Researcher, AgentRole.Executor, AgentRole.Reviewer, AgentRole.Planner)[i], a.descriptor.id) }, TaskLimits(maxRequests = 2, maxConcurrent = 4)),
        )
        assertTrue(agents.sumOf { it.calls.size } + coord.calls.size <= 2)
        assertEquals(CollaborationStatus.PartialSuccess, result.status)
    }

    @Test fun expertsRunsValidatedDependencyPlan() = runTest {
        val planner = mock("planner") { call -> AgentOutput("planner", call.role, """{"steps":[{"id":"s1","role":"Researcher","task":"research","dependsOn":[]},{"id":"s2","role":"Reviewer","task":"review","dependsOn":["s1"]}]}""") }
        val researcher = mock("research")
        val reviewer = mock("review")
        val result = CollaborationOrchestrator(listOf(mock("coord"), planner, researcher, reviewer)).run(
            request(CollaborationMode.Experts, listOf(RoleAssignment(AgentRole.Planner, "planner"), RoleAssignment(AgentRole.Researcher, "research"), RoleAssignment(AgentRole.Reviewer, "review"))),
        )
        assertEquals(CollaborationStatus.Succeeded, result.status)
        assertEquals(listOf("research answer"), reviewer.calls.single().context.map { it.text })
        assertTrue(researcher.calls.single().context.isEmpty())
    }

    @Test fun expertsRejectsUnknownDependenciesAndCycles() = runTest {
        for (json in listOf(
            """{"steps":[{"id":"a","role":"Researcher","task":"x","dependsOn":["missing"]}]}""",
            """{"steps":[{"id":"a","role":"Researcher","task":"x","dependsOn":["b"]},{"id":"b","role":"Reviewer","task":"y","dependsOn":["a"]}]}""",
        )) {
            val planner = mock("planner") { call -> AgentOutput("planner", call.role, json) }
            val result = CollaborationOrchestrator(listOf(mock("coord"), planner)).run(
                request(CollaborationMode.Experts, listOf(RoleAssignment(AgentRole.Planner, "planner"))),
            )
            assertEquals(CollaborationStatus.PartialSuccess, result.status)
        }

        val plan = """{"steps":[{"id":"exec","role":"Executor","task":"do it","dependsOn":[]},{"id":"a","role":"Researcher","task":"a","dependsOn":["b"]},{"id":"b","role":"Reviewer","task":"b","dependsOn":["a"]}]}"""
        val planner = mock("planner") { call -> AgentOutput("planner", call.role, plan) }
        val executor = mock("executor")
        val result = CollaborationOrchestrator(listOf(mock("coord"), planner, executor)).run(
            request(CollaborationMode.Experts, listOf(RoleAssignment(AgentRole.Planner, "planner"), RoleAssignment(AgentRole.Executor, "executor"), RoleAssignment(AgentRole.Researcher, "research"), RoleAssignment(AgentRole.Reviewer, "review"))),
        )
        assertEquals(CollaborationStatus.PartialSuccess, result.status)
        assertTrue(executor.calls.isEmpty())

        val missingRolePlan = """{"steps":[{"id":"e","role":"Executor","task":"execute","dependsOn":[]},{"id":"r","role":"Researcher","task":"research","dependsOn":[]}]}"""
        val secondPlanner = mock("planner") { call -> AgentOutput("planner", call.role, missingRolePlan) }
        val secondExecutor = mock("executor")
        val missingRole = CollaborationOrchestrator(listOf(mock("coord"), secondPlanner, secondExecutor)).run(
            request(CollaborationMode.Experts, listOf(RoleAssignment(AgentRole.Planner, "planner"), RoleAssignment(AgentRole.Executor, "executor"))),
        )
        assertEquals(CollaborationStatus.PartialSuccess, missingRole.status)
        assertTrue(secondExecutor.calls.isEmpty())
    }

    @Test fun requestAndTokenBudgetsAndInputLimitsAreEnforced() = runTest {
        val agent = mock("coord") { call -> AgentOutput("coord", call.role, "ok", inputTokens = 3, outputTokens = 4) }
        val limited = CollaborationOrchestrator(listOf(agent)).run(request(CollaborationMode.Single, emptyList(), TaskLimits(maxRequests = 1, maxTokens = 2)))
        assertEquals(CollaborationStatus.Failed, limited.status)
        val tooLarge = CollaborationOrchestrator(listOf(mock("coord"))).run(request(CollaborationMode.Single, emptyList(), TaskLimits(maxInputBytes = 2)))
        assertEquals(CollaborationStatus.Failed, tooLarge.status)
    }

    @Test fun invalidGoalAssignmentsAndExtremeLimitsFailBeforeDispatch() = runTest {
        val coord = mock("coord")
        val orch = CollaborationOrchestrator(listOf(coord))
        assertEquals(CollaborationStatus.Failed, orch.run(request(CollaborationMode.Single, emptyList(), goal = "  ")).status)
        assertEquals(CollaborationStatus.Failed, orch.run(request(CollaborationMode.Single, listOf(RoleAssignment(AgentRole.Reviewer, "coord"), RoleAssignment(AgentRole.Reviewer, "coord")))).status)
        assertEquals(CollaborationStatus.Failed, orch.run(request(CollaborationMode.Single, emptyList(), TaskLimits(maxConcurrent = 9))).status)
        assertTrue(coord.calls.isEmpty())
    }

    @Test fun deniesUnconfiguredAndRealAdaptersIncludingNonMockOnly() = runTest {
        val unconfigured = Fake(AgentDescriptor("coord", "coord", "x", "x", false, ConnectionKind.Mock))
        assertEquals(CollaborationStatus.Failed, CollaborationOrchestrator(listOf(unconfigured)).run(request(CollaborationMode.Single, emptyList())).status)
        val real = Fake(AgentDescriptor("coord", "coord", "x", "x", true, ConnectionKind.Model))
        val req = request(CollaborationMode.Single, emptyList()).copy(mockOnly = false, limits = TaskLimits(maxCostMicros = 100000))
        assertEquals(CollaborationStatus.Failed, CollaborationOrchestrator(listOf(real)).run(req).status)
        assertTrue(real.calls.isEmpty())
    }

    @Test fun pricedRealAdapterCanRunButNestedCallsShareTheSameBudgets() = runTest {
        val priced = Fake(
            AgentDescriptor("coord", "coord", "test", "priced", true, ConnectionKind.Model, 1_000_000, 1_000_000),
        ) { call ->
            call.additionalRequestBudget!!.reserve(8, 8)
            AgentOutput("coord", call.role, "ok", inputTokens = 3, outputTokens = 2)
        }
        val req = request(CollaborationMode.Single, emptyList(), TaskLimits(maxRequests = 2, maxConcurrent = 1, maxInputBytes = 100, maxOutputTokens = 16, maxTokens = 5000, maxCostMicros = 5000)).copy(mockOnly = false)
        val result = CollaborationOrchestrator(listOf(priced)).run(req)
        assertEquals(CollaborationStatus.Succeeded, result.status)
        assertEquals(2, result.requests)
        assertEquals(1, priced.calls.size)

        val nested = Fake(
            AgentDescriptor("coord", "coord", "test", "priced", true, ConnectionKind.Model, 1_000_000, 1_000_000),
        ) { call ->
            call.additionalRequestBudget!!.reserve(8, 8)
            AgentOutput("coord", call.role, "ok")
        }
        val capped = req.copy(limits = req.limits.copy(maxRequests = 1))
        val denied = CollaborationOrchestrator(listOf(nested)).run(capped)
        assertEquals(CollaborationStatus.Failed, denied.status)
        assertTrue(denied.events.any { it.message.contains("budget limit") })
    }

    @Test fun realCallsRequirePositiveCostBudgetAndConcurrentCallsReserveCostAtomically() = runTest {
        fun priced(id: String) = AgentDescriptor(id, id, "test", "priced", true, ConnectionKind.Model, 1_000_000, 1_000_000)
        val zeroBudget = Fake(priced("coord"))
        val denied = CollaborationOrchestrator(listOf(zeroBudget)).run(
            request(CollaborationMode.Single, emptyList(), TaskLimits(maxCostMicros = 0)).copy(mockOnly = false),
        )
        assertEquals(CollaborationStatus.Failed, denied.status)
        assertTrue(zeroBudget.calls.isEmpty())

        val first = Fake(priced("first")) { call -> AgentOutput("first", call.role, "ok", inputTokens = 1, outputTokens = 1) }
        val second = Fake(priced("second")) { call -> AgentOutput("second", call.role, "ok", inputTokens = 1, outputTokens = 1) }
        val coord = Fake(priced("coord")) { call -> AgentOutput("coord", call.role, "summary", inputTokens = 1, outputTokens = 1) }
        val parallel = request(
            CollaborationMode.Parallel,
            listOf(RoleAssignment(AgentRole.Researcher, "first"), RoleAssignment(AgentRole.Reviewer, "second")),
            TaskLimits(maxConcurrent = 2, maxInputBytes = 100, maxOutputTokens = 16, maxTokens = 10000, maxCostMicros = 1044),
        ).copy(mockOnly = false)
        val result = CollaborationOrchestrator(listOf(first, second, coord)).run(parallel)
        assertEquals(1, first.calls.size + second.calls.size)
        assertEquals(CollaborationStatus.PartialSuccess, result.status)
        assertTrue(result.events.any { it.message.contains("budget limit") })
    }

    @Test fun phoneExecutionAllowedOnlyForExecutor() = runTest {
        val coord = mock("coord")
        val executor = mock("exec")
        val reviewer = mock("review")
        CollaborationOrchestrator(listOf(coord, executor, reviewer)).run(
            request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Executor, "exec"), RoleAssignment(AgentRole.Reviewer, "review"))).copy(allowPhoneExecution = true),
        )
        assertTrue(executor.calls.single().allowPhoneExecution)
        assertFalse(reviewer.calls.single().allowPhoneExecution)
    }

    @Test fun transientFailureRetriesOnceButExecutorIsNotRetriedAndFallbackIsExplicit() = runTest {
        val count = AtomicInteger()
        val flaky = mock("flaky") { call -> if (count.incrementAndGet() == 1) throw TransientAgentException("retry") else AgentOutput("", call.role, "ok") }
        val coord = mock("coord")
        val result = CollaborationOrchestrator(listOf(coord, flaky)).run(request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Reviewer, "flaky"))))
        assertEquals(2, count.get())
        assertEquals(CollaborationStatus.Succeeded, result.status)
        val primary = mock("primary") { throw IllegalStateException("dead") }
        val fallback = mock("fallback")
        val withFallback = CollaborationOrchestrator(listOf(coord, primary, fallback)).run(
            request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Researcher, "primary", "fallback"))),
        )
        assertTrue(withFallback.outputs.any { it.agentId == "fallback" })
        val executor = mock("executor") { throw TransientAgentException("no replay") }
        CollaborationOrchestrator(listOf(coord, executor)).run(request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Executor, "executor"))))
        assertEquals(1, executor.calls.size)
        val execPrimary = mock("exec-primary") { throw IllegalStateException("uncertain side effect") }
        val execFallback = mock("exec-fallback")
        CollaborationOrchestrator(listOf(coord, execPrimary, execFallback)).run(
            request(CollaborationMode.Sequential, listOf(RoleAssignment(AgentRole.Executor, "exec-primary", "exec-fallback"))).copy(allowPhoneExecution = true),
        )
        assertEquals(1, execPrimary.calls.size)
        assertTrue("An uncertain phone side effect must not be replayed by a fallback", execFallback.calls.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutCallingCoordinatorSummary() = runTest {
        val entered = CompletableDeferred<Unit>()
        val slow = mock("slow") { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val coord = mock("coord")
        val job = launch {
            CollaborationOrchestrator(listOf(coord, slow)).run(request(CollaborationMode.Parallel, listOf(RoleAssignment(AgentRole.Researcher, "slow"))))
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(coord.calls.isEmpty())
    }

    @Test fun requestTimeoutIsReported() = runTest {
        val slow = mock("coord") { delay(100); AgentOutput("coord", it.role, "late") }
        val result = CollaborationOrchestrator(listOf(slow)).run(request(CollaborationMode.Single, emptyList(), TaskLimits(requestTimeoutMillis = 5, taskTimeoutMillis = 500)))
        assertEquals(CollaborationStatus.Failed, result.status)
        assertTrue(result.events.any { it.kind == "failed" })
    }
}
