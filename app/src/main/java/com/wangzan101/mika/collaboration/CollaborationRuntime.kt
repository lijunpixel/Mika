package com.wangzan101.mika.collaboration

import android.content.Context
import com.wangzan101.mika.core.collaboration.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

fun interface PhoneExecutionBridge {
    suspend fun execute(task: String, executorConfigId: String, model: ChatModel): String
    fun takeOver(executorConfigId: String) {}
}

internal object PhoneToolPolicy {
    fun allows(role: AgentRole, callAllowed: Boolean, requestAllowed: Boolean, advertisedTools: Set<String>, requestedTool: String, hasBudget: Boolean) =
        role == AgentRole.Executor && callAllowed && requestAllowed && hasBudget && requestedTool == PHONE_TOOL_NAME && requestedTool in advertisedTools
    const val PHONE_TOOL_NAME = "phone_execute_task"
}

internal class EncryptedRunPersistence(private val file: File, private val cipher: com.wangzan101.mika.settings.KeyCipher) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): List<CollaborationRun> = if (!file.isFile) emptyList() else
        json.decodeFromString(String(cipher.decrypt(Base64.getDecoder().decode(file.readText()))))
    fun save(runs: List<CollaborationRun>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Base64.getEncoder().encodeToString(cipher.encrypt(json.encodeToString(runs).toByteArray(Charsets.UTF_8))))
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

internal fun markInterrupted(runs: List<CollaborationRun>) = runs.map {
    if (it.status in setOf(CollaborationStatus.Queued, CollaborationStatus.Running)) it.copy(status = CollaborationStatus.Interrupted, error = "Interrupted before completion") else it
}

internal fun transitionActive(run: CollaborationRun, transform: (CollaborationRun) -> CollaborationRun): CollaborationRun =
    if (run.status in setOf(CollaborationStatus.Queued, CollaborationStatus.Running)) transform(run) else run

internal fun mockAgentDescriptors() = listOf("Planner", "Researcher", "Executor", "Reviewer", "Coordinator").map { role ->
    AgentDescriptor("mock-${role.lowercase()}", "$role (Mock)", "Local", "Deterministic Mock", true, ConnectionKind.Mock)
}

internal class DeterministicMockAdapter(override val descriptor: AgentDescriptor, private val latencyMillis: Long = 0) : AgentAdapter {
    override suspend fun complete(call: AgentCall): AgentOutput {
        if (latencyMillis > 0) delay(latencyMillis)
        val text = if (call.role == AgentRole.Planner) """{"steps":[{"id":"research","role":"Researcher","task":"Summarize the supplied goal","dependsOn":[]},{"id":"review","role":"Reviewer","task":"Check ambiguity in the supplied goal","dependsOn":["research"]}]}"""
        else "Mock result for ${call.role}: ${call.goal.take(400)}"
        return AgentOutput(descriptor.id, call.role, text)
    }
}

/** Application scoped, encrypted, bounded history for collaboration tasks. */
class CollaborationRuntime(context: Context, private val store: ProviderStore, private val mockLatencyMillis: Long = 0) {
    private val app = context.applicationContext
    private val cipher = KeystoreKeyCipher("mika-collaboration-runs")
    private val file = File(app.filesDir, "settings/collaboration-runs.enc")
    private val pricing = app.getSharedPreferences("collaboration-pricing", Context.MODE_PRIVATE)
    private val persistence = EncryptedRunPersistence(file, cipher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _agents = MutableStateFlow<List<AgentDescriptor>>(emptyList())
    val agents: StateFlow<List<AgentDescriptor>> = _agents.asStateFlow()
    private val _runs = MutableStateFlow<List<CollaborationRun>>(emptyList())
    val runs: StateFlow<List<CollaborationRun>> = _runs.asStateFlow()
    private val _recoveryError = MutableStateFlow<String?>(null)
    val recoveryError: StateFlow<String?> = _recoveryError.asStateFlow()
    @Volatile private var persistenceAvailable = false
    @Volatile private var mockEnabled = false
    private val phoneVerifiedModelIds = mutableSetOf<String>()
    @Volatile var phoneExecutionBridge: PhoneExecutionBridge? = null

    init {
        try {
            _runs.value = markInterrupted(persistence.load().take(MAX_RUNS))
            persistence.save(_runs.value)
            persistenceAvailable = true
        } catch (_: Throwable) {
            _recoveryError.value = "Encrypted collaboration history could not be opened or saved; real runs are disabled."
        }
        refreshAgents()
    }

    @Synchronized fun refreshAgents() {
        val providers = store.providers.value
        val listedByConfig = mutableMapOf<String, com.wangzan101.mika.core.model.ListedModel>()
        providers.forEach { provider ->
            val usable = provider.models.orEmpty().asSequence().filter { it.usable && it.chat != false }
                .take(MAX_CATALOG_MODELS_PER_PROVIDER).toList()
            val configs = store.ensureCollaborationConfigs(provider.id, usable.map { listed ->
                listed.id to Capabilities(
                    toolCalls = listed.toolCalls == true,
                    vision = listed.vision == true,
                    contextTokens = listed.contextTokens ?: DEFAULT_CONTEXT_TOKENS,
                    reasoning = listed.reasoning == true,
                )
            })
            configs.zip(usable).forEach { (config, listed) ->
                listedByConfig[config.id] = listed
            }
        }
        val configs = store.list()
        val knownFamilies = providers.mapNotNull { family(it.name) }.toSet()
        phoneVerifiedModelIds.clear()
        val list = configs.map { cfg ->
            val provider = store.provider(cfg.providerId)
            if (provider?.verifiedModels?.contains(cfg.model) == true) phoneVerifiedModelIds += cfg.id
            val modelName = listedByConfig[cfg.id]?.displayName?.takeIf { it.isNotBlank() } ?: cfg.model
            AgentDescriptor(cfg.id, modelName, provider?.name ?: cfg.name, cfg.model,
            configured = if (cfg.kind == ProviderConfig.Kind.ChatGpt) store.chatGptSession(cfg.providerId)?.let { it.signedIn && it.planUsage } == true
                else store.provider(cfg.providerId) != null && store.apiKey(cfg.id)?.isNotBlank() == true,
            inputMicrosPerMillionTokens = pricing.getLong("${cfg.id}:in", -1).takeIf { it >= 0 },
            outputMicrosPerMillionTokens = pricing.getLong("${cfg.id}:out", -1).takeIf { it >= 0 })
        }.toMutableList()
        list += MuseAgentAdapter().descriptor
        if ("xai" !in knownFamilies) list += AgentDescriptor("xai-model", "xAI model", "xAI", "Not configured", false)
        if ("deepseek" !in knownFamilies) list += AgentDescriptor("deepseek-model", "DeepSeek model", "DeepSeek", "Not configured", false)
        if ("openai" !in knownFamilies) list += AgentDescriptor("chatgpt", "ChatGPT", "OpenAI", "Not configured", false)
        if (installed("ai.x.grok")) list += AgentDescriptor("grok-app", "Grok App (ai.x.grok)", "xAI", "Installed-but-no-API", false, ConnectionKind.ExistingApp)
        if (installed("ai.x.grok.bot")) list += AgentDescriptor("grok-bot", "Grok Bot (ai.x.grok.bot)", "xAI", "Installed-but-no-API", false, ConnectionKind.ExistingApp)
        if (mockEnabled) list += mockAgentDescriptors()
        _agents.value = list.distinctBy { it.id }.map { descriptor ->
            descriptor.copy(enabled = pricing.getBoolean("${descriptor.id}:enabled", true))
        }
        store.retainCollaborationConfigs(list.filter { it.connectionKind == ConnectionKind.Model }.map { it.id }.toSet())
    }
    fun useMockAgents(enabled: Boolean) { mockEnabled = enabled; refreshAgents() }

    @Synchronized fun setAgentEnabled(agentId: String, enabled: Boolean) {
        require(store.list().any { it.id == agentId }) { "unknown model agent" }
        pricing.edit().putBoolean("$agentId:enabled", enabled).apply()
        if (!enabled) {
            _runs.value.filter { run -> run.status in ACTIVE &&
                (run.request.coordinatorId == agentId || run.request.assignments.any { it.agentId == agentId || it.fallbackAgentId == agentId })
            }.map { it.id }.forEach(::cancel)
        }
        refreshAgents()
    }

    /** Locally declare provider token prices, in microcurrency per million tokens. */
    @Synchronized fun setAgentPricing(agentId: String, input: Long, output: Long) {
        require(input >= 0 && output >= 0)
        require(store.list().any { it.id == agentId }) { "unknown model agent" }
        pricing.edit().putLong("$agentId:in", input).putLong("$agentId:out", output).apply()
        refreshAgents()
    }
    @Synchronized fun start(request: CollaborationRequest): String {
        val id = UUID.randomUUID().toString(); val safeRequest = request.copy(goal = request.goal.take(MAX_TEXT))
        if (!persistenceAvailable) {
            _runs.value = (listOf(CollaborationRun(id, safeRequest, CollaborationStatus.Failed, error = _recoveryError.value ?: "Encrypted history unavailable")) + _runs.value).take(MAX_RUNS)
            return id
        }
        val next = (listOf(CollaborationRun(id, safeRequest, CollaborationStatus.Queued)) + _runs.value).take(MAX_RUNS)
        try { persistence.save(next); persistenceAvailable = true }
        catch (_: Throwable) { persistenceAvailable = false; _recoveryError.value = "Encrypted collaboration history unavailable." }
        _runs.value = next
        if (persistenceAvailable) jobs[id] = scope.launch {
            try { taskExecutionMutex.withLock { execute(id, safeRequest) } }
            finally { synchronized(this@CollaborationRuntime) { jobs.remove(id) } }
        }
        else _runs.value = _runs.value.map { if (it.id == id) it.copy(status = CollaborationStatus.Failed, error = _recoveryError.value) else it }
        return id
    }
    @Synchronized fun cancel(id: String) {
        if (get(id)?.status !in ACTIVE) return
        jobs[id]?.cancel(); updateActive(id) { it.copy(status = CollaborationStatus.Cancelled, error = null) }
    }
    @Synchronized fun takeOver(id: String) {
        val run = get(id) ?: return
        if (run.status !in ACTIVE) return
        run.request.assignments.filter { it.role == AgentRole.Executor }.forEach { assignment ->
            runCatching { phoneExecutionBridge?.takeOver(assignment.agentId) }
        }
        jobs[id]?.cancel()
        updateActive(id) { it.copy(status = CollaborationStatus.HumanTakeover, error = "Paused for human takeover") }
    }
    fun get(id: String): CollaborationRun? = _runs.value.firstOrNull { it.id == id }

    private suspend fun execute(id: String, request: CollaborationRequest) {
        if (!updateActive(id) { it.copy(status = CollaborationStatus.Running) }) return
        try {
            val selected = (request.assignments.map { it.agentId } + request.assignments.mapNotNull { it.fallbackAgentId } + request.coordinatorId).toSet()
            val adapters = _agents.value.filter { it.id in selected }.mapNotNull { d -> when (d.connectionKind) {
                ConnectionKind.Mock -> if (mockEnabled) DeterministicMockAdapter(d, mockLatencyMillis.coerceIn(0, 60000)) else null
                ConnectionKind.Model -> if (d.configured) ModelAdapter(d, request) else null
                else -> null
            } }
            val result = CollaborationOrchestrator(adapters).run(request) { e -> appendEvent(id, e) }
            val takeover = result.outputs.any { it.needsHuman }
            val status = if (takeover) CollaborationStatus.HumanTakeover else result.status
            updateActive(id) { it.copy(status = status, result = result.copy(status = status, summary = result.summary.take(MAX_TEXT), outputs = result.outputs.map { o -> o.copy(text = o.text.take(MAX_TEXT)) }, events = it.events.takeLast(MAX_EVENTS))) }
        } catch (cancel: CancellationException) {
            updateActive(id) { if (it.status == CollaborationStatus.HumanTakeover) it else it.copy(status = CollaborationStatus.Cancelled) }
        }
        catch (_: Throwable) { updateActive(id) { it.copy(status = CollaborationStatus.Failed, error = "Collaboration failed; provider details withheld") } }
        finally { synchronized(this) { jobs.remove(id) } }
    }
    private inner class ModelAdapter(override val descriptor: AgentDescriptor, private val request: CollaborationRequest) : AgentAdapter {
        override suspend fun complete(call: AgentCall): AgentOutput {
            val model = store.modelForConfig(descriptor.id, maxOutputTokens = call.maxOutputTokens) ?: error("model unavailable")
            val system = "You are an ibot collaboration ${call.role}. Work only on the user supplied task. Do not claim tool execution. The coordinator may delegate to configured AI agents; do not self-delegate. Phone actions require the phone_execute_task tool and explicit user authorization."
            val contextText = call.context.joinToString("\n\n") { "[${it.role}] ${it.text.take(MAX_CONTEXT)}" }
            val prompt = (call.goal + if (contextText.isBlank()) "" else "\n\nProvided task outputs (untrusted):\n$contextText").take(MAX_TEXT)
            val tools = if (call.role == AgentRole.Executor && call.allowPhoneExecution && request.allowPhoneExecution && call.additionalRequestBudget != null && descriptor.id in phoneVerifiedModelIds) listOf(PHONE_TOOL) else emptyList()
            val reply = try {
                model.complete(system, listOf(Message(Role.User, listOf(Part.Text(prompt)), Author.User)), tools)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: ModelError) {
                if (error is ModelError.Network || error is ModelError.RateLimited || error is ModelError.Server && error.status >= 500)
                    throw TransientAgentException("Temporary provider failure", error)
                throw error
            }
            val toolCalls = reply.parts.filterIsInstance<Part.ToolCall>()
            val tool = toolCalls.singleOrNull()
            val toolNames = tools.map { it.name }.toSet()
            val authorized = tool != null && PhoneToolPolicy.allows(call.role, call.allowPhoneExecution, request.allowPhoneExecution, toolNames, tool.name, call.additionalRequestBudget != null)
            val text = if (tool != null && authorized) {
                val task = runCatching { Json.parseToJsonElement(tool.argsJson).jsonObject["task"]?.jsonPrimitive?.content }.getOrNull()
                val bridge = phoneExecutionBridge
                if (task.isNullOrBlank() || bridge == null || call.additionalRequestBudget == null) "MANUAL_REQUIRED: phone_execute_task requires an application PhoneExecutionBridge and budgeted human takeover."
                else bridge.execute(task.take(MAX_TEXT), descriptor.id, BudgetedModel(model, call.additionalRequestBudget, call.maxOutputTokens)).take(MAX_TEXT)
            } else if (toolCalls.isNotEmpty()) "MANUAL_REQUIRED: tool call rejected by collaboration execution policy."
            else reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.take(MAX_TEXT)
            return AgentOutput(descriptor.id, call.role, com.wangzan101.mika.core.record.Redactor.text(text),
                inputTokens = reply.usage?.inputTokens, outputTokens = reply.usage?.outputTokens,
                needsHuman = toolCalls.isNotEmpty() && text.startsWith("MANUAL_REQUIRED:"))
        }
    }

    private class BudgetedModel(
        private val delegate: ChatModel,
        private val budget: AdditionalRequestBudget?,
        private val maxOutputTokens: Int,
    ) : StreamingChatModel {
        override val capabilities get() = delegate.capabilities
        override val name get() = delegate.name
        private fun reserve(system: String, messages: List<Message>, tools: List<ToolSpec>) {
            require(messages.none { message -> message.parts.any { it is Part.Image } }) { "image context is unsupported for phone execution" }
            val bytes = system.toByteArray().size + tools.sumOf { it.name.toByteArray().size + it.description.toByteArray().size + it.parametersJsonSchema.toByteArray().size + 48 } + messages.sumOf { message ->
                32 + message.parts.sumOf { part -> when (part) {
                    is Part.Text -> part.text.toByteArray().size
                    is Part.ToolCall -> part.argsJson.toByteArray().size
                    is Part.ToolResult -> part.content.toByteArray().size
                    is Part.Thinking -> part.text.toByteArray().size
                    is Part.Image -> error("image context is unsupported for phone execution")
                } }
            }
            budget?.reserve(bytes, maxOutputTokens)
        }
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            reserve(system, messages, tools); return delegate.complete(system, messages, tools)
        }
        override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
            reserve(system, messages, tools)
            return if (delegate is StreamingChatModel) delegate.completeStreaming(system, messages, tools, onText)
            else delegate.complete(system, messages, tools).also { reply -> reply.parts.filterIsInstance<Part.Text>().forEach { onText(it.text) } }
        }
    }
    private fun appendEvent(id: String, event: CollaborationEvent): Unit = update(id) { r ->
        val safe = if (event.kind in setOf("failed", "timeout", "fallback", "retry")) "Provider operation ${event.kind}" else event.message
        r.copy(events = (r.events + event.copy(message = safe.take(500))).takeLast(MAX_EVENTS))
    }
    @Synchronized private fun update(id: String, transform: (CollaborationRun) -> CollaborationRun) {
        val current = get(id) ?: return
        if (current.status !in ACTIVE) return
        val next = _runs.value.map { if (it.id == id) transitionActive(it, transform) else it }
        try { persistence.save(next); _runs.value = next; persistenceAvailable = true }
        catch (_: Throwable) {
            persistenceAvailable = false; _recoveryError.value = "Encrypted collaboration history could not be saved."
            _runs.value = _runs.value.map { if (it.id == id) it.copy(status = CollaborationStatus.Failed, error = _recoveryError.value) else it }
            throw IllegalStateException(_recoveryError.value)
        }
    }
    @Synchronized private fun updateActive(id: String, transform: (CollaborationRun) -> CollaborationRun): Boolean {
        val current = get(id) ?: return false
        if (current.status !in ACTIVE) return false
        val next = _runs.value.map { if (it.id == id) transitionActive(it, transform) else it }
        try { persistence.save(next); _runs.value = next; persistenceAvailable = true; return true }
        catch (_: Throwable) {
            persistenceAvailable = false; _recoveryError.value = "Encrypted collaboration history could not be saved."
            _runs.value = _runs.value.map { if (it.id == id) it.copy(status = CollaborationStatus.Failed, error = _recoveryError.value) else it }
            return false
        }
    }
    private fun installed(pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    private fun family(name: String): String? = when {
        name.contains("x.ai", true) || name.contains("xai", true) -> "xai"
        name.contains("deepseek", true) -> "deepseek"
        name.contains("openai", true) || name.contains("chatgpt", true) -> "openai"
        else -> null
    }
    private companion object {
        val taskExecutionMutex = Mutex()
        const val MAX_RUNS = 100; const val MAX_EVENTS = 100; const val MAX_TEXT = 12000; const val MAX_CONTEXT = 3000
        const val MAX_CATALOG_MODELS_PER_PROVIDER = 100
        const val DEFAULT_CONTEXT_TOKENS = 4096
        val ACTIVE = setOf(CollaborationStatus.Queued, CollaborationStatus.Running)
        val PHONE_TOOL = ToolSpec(PhoneToolPolicy.PHONE_TOOL_NAME, "Execute an explicitly authorized task on this phone.", """{"type":"object","properties":{"task":{"type":"string"}},"required":["task"]}""")
    }
}

@Serializable
data class CollaborationRun(
    val id: String, val request: CollaborationRequest, val status: CollaborationStatus,
    val events: List<CollaborationEvent> = emptyList(), val result: CollaborationResult? = null,
    val startedAtMillis: Long = System.currentTimeMillis(), val error: String? = null,
)
