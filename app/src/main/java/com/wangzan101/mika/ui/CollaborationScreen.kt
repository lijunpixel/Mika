package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import com.wangzan101.mika.R
import com.wangzan101.mika.collaboration.CollaborationRuntime
import com.wangzan101.mika.core.collaboration.*
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold

@Composable
fun CollaborationScreen(runtime: CollaborationRuntime, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val agents by runtime.agents.collectAsState()
    val runs by runtime.runs.collectAsState()
    var mock by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CollaborationMode.Single) }
    var coordinator by remember { mutableStateOf("") }
    var assignments by remember { mutableStateOf(mapOf<AgentRole, String>()) }
    var maxRequests by remember { mutableStateOf("20") }
    var concurrency by remember { mutableStateOf("2") }
    var maxTokens by remember { mutableStateOf("32000") }
    var maxCost by remember { mutableStateOf("0") }
    var prices by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var phoneExec by remember { mutableStateOf(false) }
    var selectedRun by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime) { runtime.refreshAgents() }
    LaunchedEffect(mock) { runtime.useMockAgents(mock) }
    val eligible = agents.filter { it.configured && it.enabled && (it.connectionKind == ConnectionKind.Mock) == mock }
    LaunchedEffect(agents, mock) {
        val available = agents.filter { it.configured && it.enabled && (it.connectionKind == ConnectionKind.Mock) == mock }
        val ids = available.map { it.id }.toSet()
        if (mock) {
            val first = available.firstOrNull()?.id.orEmpty()
            val selectedCoordinator = available.firstOrNull { it.id == "mock-coordinator" }?.id ?: first
            if (coordinator !in ids && selectedCoordinator.isNotEmpty()) coordinator = selectedCoordinator
            assignments = buildMap {
                AgentRole.entries.forEach { role ->
                    val id = assignments[role]?.takeIf { it in ids }
                        ?: available.firstOrNull { it.id == "mock-${role.name.lowercase()}" }?.id
                        ?: first.takeIf { it.isNotBlank() }
                    if (id != null) put(role, id)
                }
            }
        } else {
            if (coordinator !in ids) coordinator = available.firstOrNull { isChatGpt(it) }?.id.orEmpty()
            assignments = assignments.filterValues { it in ids }
        }
    }
    val active = runs.firstOrNull { it.id == selectedRun } ?: runs.firstOrNull()
    fun savePrice(agentId: String, value: Pair<String, String>) {
        prices = prices + (agentId to value)
        val input = usdToMicros(value.first)
        val output = usdToMicros(value.second)
        if (input != null && output != null) runtime.setAgentPricing(agentId, input, output)
    }
    PageScaffold("ibot", onBack, Modifier.testTag("ibot_collaboration")) {
        GroupCard {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.collab_subtitle))
                MikaButton(stringResource(R.string.collab_models), onOpenModels, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Tonal)
            }
        }
        GroupLabel(stringResource(R.string.collab_connections))
        GroupCard {
            val realAgents = agents.filter { it.connectionKind != ConnectionKind.Mock }
            ConnectionStatus(stringResource(R.string.collab_muse), realAgents.filter { it.id == "muse" }, R.string.collab_unconfigured)
            ConnectionStatus(stringResource(R.string.collab_grok_app), realAgents.filter { it.id == "grok-app" }, R.string.collab_not_installed)
            ConnectionStatus(stringResource(R.string.collab_grok_bot), realAgents.filter { it.id == "grok-bot" }, R.string.collab_not_installed)
            ConnectionStatus(stringResource(R.string.collab_api), realAgents.filter { it.connectionKind == ConnectionKind.Model }, R.string.collab_unconfigured)
            ConnectionStatus(stringResource(R.string.collab_deepseek), realAgents.filter { it.connectionKind == ConnectionKind.Model && it.provider.contains("deepseek", true) }, R.string.collab_unconfigured)
            ConnectionStatus(stringResource(R.string.collab_chatgpt), realAgents.filter(::isChatGpt), R.string.collab_unconfigured)
            val mockAgents = agents.filter { it.connectionKind == ConnectionKind.Mock }
            Row(Modifier.fillMaxWidth().clickable { mock = !mock }.testTag("ibot_mock_toggle").padding(16.dp)) {
                Checkbox(mock, { mock = it })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.collab_mock))
                    Text(stringResource(R.string.collab_mock_note, mockAgents.size), Modifier.padding(top = 2.dp))
                }
            }
        }
        if (mock) Text(stringResource(R.string.collab_mock_enabled), Modifier.padding(vertical = 8.dp))
        runtime.recoveryError.collectAsState().value?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
        val configuredModels = agents.filter { it.configured && it.connectionKind == ConnectionKind.Model }
        if (configuredModels.isNotEmpty()) {
            GroupLabel(stringResource(R.string.collab_ai_models))
            GroupCard {
                configuredModels.forEach { agent ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${agent.provider} · ${agent.model}")
                            Text(if (agent.enabled) stringResource(R.string.collab_agent_enabled) else stringResource(R.string.collab_agent_disabled))
                        }
                        Checkbox(agent.enabled, { runtime.setAgentEnabled(agent.id, it) })
                    }
                }
            }
        }
        GroupLabel(stringResource(R.string.collab_mode))
        GroupCard {
            CollaborationMode.entries.forEach { item ->
                Text("${if (mode == item) "●" else "○"}  ${modeLabel(item)}", Modifier.fillMaxWidth().clickable { mode = item }.testTag("ibot_mode_${item.name}").padding(14.dp))
            }
        }
        GroupLabel(stringResource(R.string.collab_assignments))
        GroupCard {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.collab_coordinator))
                eligible.forEach { a -> Text("${if (coordinator == a.id) "●" else "○"} ${a.displayName} (${a.model})", Modifier.fillMaxWidth().clickable { coordinator = a.id }.padding(8.dp)) }
                AgentRole.entries.filter { it != AgentRole.Coordinator && mode != CollaborationMode.Single }.forEach { role ->
                    Text("${roleLabel(role)}: ${eligible.firstOrNull { it.id == assignments[role] }?.displayName ?: stringResource(R.string.collab_unselected)}", Modifier.padding(top = 8.dp))
                    eligible.forEach { a -> Text("${if (assignments[role] == a.id) "●" else "○"} ${a.displayName}", Modifier.fillMaxWidth().clickable { assignments = assignments + (role to a.id) }.padding(start = 12.dp, top = 5.dp, bottom = 5.dp)) }
                }
                Text(stringResource(R.string.collab_chatgpt_default), Modifier.padding(top = 8.dp))
            }
        }
        GroupLabel(stringResource(R.string.collab_goal))
        OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth().testTag("ibot_goal"), label = { Text(stringResource(R.string.collab_goal_hint)) })
        GroupLabel(stringResource(R.string.collab_budgets))
        GroupCard { Column(Modifier.padding(16.dp)) {
            BudgetField(stringResource(R.string.collab_max_requests), maxRequests) { maxRequests = it }
            BudgetField(stringResource(R.string.collab_concurrency), concurrency) { concurrency = it }
            BudgetField(stringResource(R.string.collab_max_tokens), maxTokens) { maxTokens = it }
            BudgetField(stringResource(R.string.collab_max_cost), maxCost) { maxCost = it }
            Text(stringResource(R.string.collab_price_note), Modifier.padding(top = 4.dp))
            configuredModels.forEach { agent ->
                val current = prices[agent.id] ?: (microsToUsd(agent.inputMicrosPerMillionTokens) to microsToUsd(agent.outputMicrosPerMillionTokens))
                Text("${agent.provider} · ${agent.model}", Modifier.padding(top = 10.dp))
                BudgetField(stringResource(R.string.collab_input_price), current.first,
                    { value -> savePrice(agent.id, value to current.second) })
                BudgetField(stringResource(R.string.collab_output_price), current.second,
                    { value -> savePrice(agent.id, current.first to value) })
            }
            Row(Modifier.fillMaxWidth().clickable { phoneExec = !phoneExec }) {
                Checkbox(phoneExec, { phoneExec = it })
                Text(stringResource(R.string.collab_phone_exec), Modifier.padding(top = 12.dp))
            }
            Text(stringResource(R.string.collab_phone_note))
        } }
        val picked = active
        if (picked != null) {
            GroupLabel(stringResource(R.string.collab_run))
            GroupCard {
                Column(Modifier.padding(16.dp).testTag("ibot_result")) {
                    Text("${picked.status} · ${picked.request.goal}")
                    picked.events.forEach { Text("${it.sequence}. ${it.kind}: ${it.message}", Modifier.padding(top = 6.dp)) }
                    val assigned = (picked.request.assignments.map { it.agentId } + picked.request.coordinatorId).distinct()
                    assigned.forEach { id ->
                        val output = picked.result?.outputs?.lastOrNull { it.agentId == id }
                        val failed = picked.events.any { it.kind == "failed" && it.agentId == id }
                        val status = when { failed -> stringResource(R.string.collab_agent_failed); output != null -> stringResource(R.string.collab_agent_done); picked.status in setOf(CollaborationStatus.Queued, CollaborationStatus.Running) -> stringResource(R.string.collab_agent_running); else -> stringResource(R.string.collab_agent_pending) }
                        Text("${agents.firstOrNull { it.id == id }?.displayName ?: id} · $status", Modifier.padding(top = 8.dp))
                    }
                    picked.result?.let { result ->
                        result.outputs.forEach {
                            val source = agents.firstOrNull { agent -> agent.id == it.agentId }
                            Text("${roleLabel(it.role)} · ${source?.displayName ?: it.agentId} (${source?.model.orEmpty()}) · ${stringResource(R.string.collab_unverified)}\n${it.text}", Modifier.padding(top = 10.dp))
                        }
                        Text("${stringResource(R.string.collab_summary)} · ${stringResource(R.string.collab_unverified)}\n${result.summary}", Modifier.padding(top = 12.dp))
                        Text(stringResource(R.string.collab_metrics, result.requests, result.reservedTokens, microsToUsd(result.estimatedCostMicros)), Modifier.padding(top = 12.dp))
                    }
                    picked.error?.let { Text("${stringResource(R.string.collab_error)}: $it", Modifier.padding(top = 8.dp)) }
                    val activeRun = picked.status in setOf(CollaborationStatus.Queued, CollaborationStatus.Running)
                    MikaButton(stringResource(R.string.collab_cancel), { runtime.cancel(picked.id) }, Modifier.testTag("ibot_cancel").padding(top = 8.dp), kind = ButtonKind.Tonal, enabled = activeRun)
                    if (activeRun) {
                        MikaButton(stringResource(R.string.collab_takeover), { runtime.takeOver(picked.id) }, Modifier.testTag("ibot_takeover").padding(top = 8.dp), kind = ButtonKind.Tonal)
                    }
                }
            }
        }
        val selectedIds = (if (mode == CollaborationMode.Single) listOf(coordinator) else assignments.filterValues { it.isNotBlank() }.values + coordinator).toSet()
        val costMicros = usdToMicros(maxCost)
        val selectedPrices = eligible.filter { it.id in selectedIds && it.connectionKind == ConnectionKind.Model }.associate { a ->
            a.id to (usdToMicros(prices[a.id]?.first ?: microsToUsd(a.inputMicrosPerMillionTokens)) to usdToMicros(prices[a.id]?.second ?: microsToUsd(a.outputMicrosPerMillionTokens)))
        }
        val costAllowed = mock || (costMicros?.let { it > 0 } == true && selectedPrices.isNotEmpty() && selectedPrices.values.all { it.first?.let { p -> p > 0 } == true && it.second?.let { p -> p > 0 } == true })
        val limitsValid = maxRequests.toIntOrNull()?.let { it > 0 } == true &&
            concurrency.toIntOrNull()?.let { it > 0 } == true &&
            maxTokens.toIntOrNull()?.let { it > 0 } == true && costMicros?.let { it >= 0 } == true
        val eligibleIds = eligible.map { it.id }.toSet()
        val assignmentsReady = coordinator in eligibleIds && when (mode) {
            CollaborationMode.Single -> true
            CollaborationMode.Experts -> listOf(AgentRole.Planner, AgentRole.Researcher, AgentRole.Executor, AgentRole.Reviewer).all { assignments[it] in eligibleIds }
            else -> assignments.filterKeys { it != AgentRole.Coordinator }.values.any { it in eligibleIds }
        }
        if (!mock) Text(stringResource(R.string.collab_unknown_price), Modifier.padding(vertical = 8.dp))
        if (mode == CollaborationMode.Experts) {
            val missing = listOf(AgentRole.Planner, AgentRole.Researcher, AgentRole.Executor, AgentRole.Reviewer).filter { assignments[it].orEmpty().isBlank() }
            val labels = missing.map { roleLabel(it) }
            if (missing.isNotEmpty()) Text(stringResource(R.string.collab_missing_roles, labels.joinToString()), Modifier.padding(vertical = 4.dp))
        }
        MikaButton(stringResource(R.string.collab_start), {
            val ids = if (mode == CollaborationMode.Single) emptyList() else assignments.filter { it.value.isNotBlank() }.map { RoleAssignment(it.key, it.value) }
            if (!mock) {
                selectedPrices.forEach { (agentId, values) -> runtime.setAgentPricing(agentId, values.first!!, values.second!!) }
            }
            val id = runtime.start(CollaborationRequest(goal, mode, ids, coordinator,
                TaskLimits(maxRequests = maxRequests.toInt(), maxConcurrent = concurrency.toInt(), maxTokens = maxTokens.toInt(), maxCostMicros = costMicros ?: 0), mockOnly = mock, allowPhoneExecution = phoneExec))
            selectedRun = id
        }, Modifier.fillMaxWidth().testTag("ibot_start").padding(top = 12.dp), enabled = goal.isNotBlank() && assignmentsReady && eligible.isNotEmpty() && costAllowed && limitsValid)
    }
}

@Composable
private fun BudgetField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
}

@Composable
private fun ConnectionStatus(label: String, candidates: List<AgentDescriptor>, absentStatus: Int) {
    val status = when {
        candidates.isEmpty() -> stringResource(absentStatus)
        candidates.any { it.connectionKind == ConnectionKind.ExistingApp } -> stringResource(R.string.collab_installed_api_unverified)
        candidates.any { it.configured && it.enabled } -> stringResource(R.string.collab_available)
        candidates.any { it.configured } -> stringResource(R.string.collab_agent_disabled)
        else -> stringResource(R.string.collab_unconfigured)
    }
    Text("$label · $status", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

private fun isChatGpt(agent: AgentDescriptor) =
    agent.provider.contains("chatgpt", ignoreCase = true) || agent.displayName.contains("chatgpt", ignoreCase = true)

private fun microsToUsd(value: Long?): String = value?.let { BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() } ?: "0"

private fun usdToMicros(value: String): Long? = runCatching {
    require(value.length in 1..32)
    BigDecimal(value).multiply(BigDecimal("1000000")).setScale(0, RoundingMode.UNNECESSARY).longValueExact().takeIf { it >= 0 }
}.getOrNull()

@Composable
private fun modeLabel(mode: CollaborationMode): String = stringResource(when (mode) {
    CollaborationMode.Single -> R.string.collab_mode_single
    CollaborationMode.Sequential -> R.string.collab_mode_sequential
    CollaborationMode.Parallel -> R.string.collab_mode_parallel
    CollaborationMode.Experts -> R.string.collab_mode_experts
})

@Composable
private fun roleLabel(role: AgentRole): String = stringResource(when (role) {
    AgentRole.Planner -> R.string.collab_role_planner
    AgentRole.Researcher -> R.string.collab_role_researcher
    AgentRole.Executor -> R.string.collab_role_executor
    AgentRole.Reviewer -> R.string.collab_role_reviewer
    AgentRole.Coordinator -> R.string.collab_role_coordinator
})
