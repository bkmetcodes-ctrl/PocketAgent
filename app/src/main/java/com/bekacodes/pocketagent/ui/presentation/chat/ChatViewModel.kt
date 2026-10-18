package com.bekacodes.pocketagent.ui.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bekacodes.pocketagent.BuildConfig
import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.GeminiSseClient
import com.bekacodes.pocketagent.data.LlmStreamEvent
import com.bekacodes.pocketagent.domain.AgentToolRegistry
import com.bekacodes.pocketagent.domain.AuditResult
import com.bekacodes.pocketagent.domain.ContextWindowManager
import com.bekacodes.pocketagent.domain.ToolCallAuditor
import com.bekacodes.pocketagent.ui.presentation.chat.components.ReadToolBadge
import com.bekacodes.pocketagent.ui.presentation.chat.model.ApprovalResolution
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatUiState
import com.bekacodes.pocketagent.ui.presentation.chat.model.EngineMode
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import com.bekacodes.pocketagent.ui.presentation.chat.model.PendingWriteCardState
import com.bekacodes.pocketagent.ui.presentation.chat.model.StreamPipelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID
import kotlin.time.measureTimedValue

/**
 * MVI ViewModel managing PocketAgent chat UI state, Gemini SSE streaming, tool executions, and room database observing.
 */
class ChatViewModel(
    private val expenseDao: ExpenseDao,
    private val toolRegistry: AgentToolRegistry,
    private val geminiSseClient: GeminiSseClient = GeminiSseClient(BuildConfig.GEMINI_API_KEY),
    private val contextWindowManager: ContextWindowManager = ContextWindowManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            engineMode = if (BuildConfig.GEMINI_API_KEY.isNotBlank()) EngineMode.GEMINI_LIVE else EngineMode.DEMO_SIM
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch {
            expenseDao.observeAllExpenses().collect { rows ->
                _uiState.update { it.copy(expenses = rows) }
            }
        }
    }

    /**
     * Handles MVI user intents dispatched from the UI layer.
     */
    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> _uiState.update { it.copy(inputText = intent.text) }
            is ChatIntent.SwitchEngineMode -> _uiState.update { it.copy(engineMode = intent.mode) }
            is ChatIntent.ToggleInspector -> _uiState.update { it.copy(isInspectorVisible = intent.visible) }
            is ChatIntent.ClearDatabase -> {
                viewModelScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        expenseDao.clearAll()
                    }
                }
            }
            is ChatIntent.SendPrompt -> startStreamingTurn(intent.prompt)
            is ChatIntent.StopGeneration -> stopGenerationInternal()
            is ChatIntent.ResolveWriteApproval -> {
                resolveWriteApproval(intent.messageId, intent.approved)
            }
        }
    }

    private fun stopGenerationInternal() {
        activeGenerationJob?.cancel()
        pendingApprovalDeferred?.cancel()
        pendingApprovalDeferred = null
    }

    private fun startStreamingTurn(prompt: String) {
        stopGenerationInternal()

        val userMsgId = UUID.randomUUID().toString()
        val agentMsgId = UUID.randomUUID().toString()

        val userMessage = ChatMessageUiModel.UserMessage(
            id = userMsgId,
            timestamp = "9:41 AM",
            text = prompt
        )
        val initialAgentMessage = ChatMessageUiModel.AgentMessage(
            id = agentMsgId,
            timestamp = "9:41 AM",
            status = MessageStreamStatus.Streaming
        )

        val updatedMessages = _uiState.value.messages + userMessage + initialAgentMessage
        val trimmedMessages = contextWindowManager.trimToFitBudget(updatedMessages)
        val estimatedTokens = contextWindowManager.estimateTotalTokens(trimmedMessages)

        _uiState.update { state ->
            state.copy(
                inputText = "",
                isGenerating = true,
                usedTokens = estimatedTokens,
                messages = trimmedMessages
            )
        }

        activeGenerationJob = viewModelScope.launch {
            try {
                val mode = _uiState.value.engineMode
                val stream = if (mode == EngineMode.GEMINI_LIVE) {
                    orchestrateLiveGeminiTurn(prompt)
                } else {
                    orchestrateDemoSimTurn(prompt, agentMsgId)
                }

                stream
                    .scan(initialAgentMessage) { acc, event ->
                        when (event) {
                            is StreamPipelineEvent.ReadToolStarted -> acc.copy(
                                executedReadTools = acc.executedReadTools + ReadToolBadge(
                                    callSignature = event.signature,
                                    durationMs = 0,
                                    isRunning = true
                                )
                            )

                            is StreamPipelineEvent.ReadToolCompleted -> acc.copy(
                                executedReadTools = acc.executedReadTools.map {
                                    if (it.callSignature == event.signature) {
                                        it.copy(durationMs = event.durationMs, isRunning = false)
                                    } else it
                                }
                            )

                            is StreamPipelineEvent.TokenDelta -> acc.copy(
                                markdownText = acc.markdownText + event.delta,
                                status = MessageStreamStatus.Streaming
                            )

                            is StreamPipelineEvent.RequestWriteApproval -> acc.copy(
                                pendingWriteApproval = event.card,
                                status = MessageStreamStatus.AwaitingApproval
                            )
                        }
                    }
                    .flowOn(Dispatchers.Default)
                    .collect { updatedMsg ->
                        updateAgentMessage(agentMsgId) { updatedMsg }
                    }

                updateAgentMessage(agentMsgId) { it.copy(status = MessageStreamStatus.Completed) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    updateAgentMessage(agentMsgId) { msg ->
                        msg.copy(status = MessageStreamStatus.InterruptedByUser)
                    }
                }
                throw e
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { state ->
                        state.copy(
                            isGenerating = false,
                            usedTokens = contextWindowManager.estimateTotalTokens(state.messages)
                        )
                    }
                }
            }
        }
    }

    private fun resolveWriteApproval(messageId: String, approved: Boolean) {
        updateAgentMessage(messageId) { msg ->
            msg.copy(
                pendingWriteApproval = msg.pendingWriteApproval?.copy(
                    resolution = if (approved) ApprovalResolution.APPROVED else ApprovalResolution.REJECTED
                )
            )
        }
        pendingApprovalDeferred?.complete(approved)
    }

    /**
     * LIVE GEMINI ORCHESTRATOR:
     * Streams SSE tokens, intercepts `functionCall` requests, executes read tools or suspends on
     * write tools for user approval, and streams the final response.
     */
    private fun orchestrateLiveGeminiTurn(
        prompt: String
    ): Flow<StreamPipelineEvent> = callbackFlow {
        val job = launch {
            val pendingToolCalls = mutableListOf<LlmStreamEvent.FunctionCallRequested>()
            val streamedTextThisHop = StringBuilder()

            geminiSseClient.streamTurn(
                prompt = prompt,
                tools = toolRegistry.allTools()
            ).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        streamedTextThisHop.append(event.text)
                        send(StreamPipelineEvent.TokenDelta(event.text))
                    }

                    is LlmStreamEvent.FunctionCallRequested -> {
                        pendingToolCalls.add(event)
                    }

                    is LlmStreamEvent.UsageMetadata -> {
                        _uiState.update { it.copy(usedTokens = event.totalTokens) }
                    }

                    is LlmStreamEvent.StreamError -> {
                        send(StreamPipelineEvent.TokenDelta("\n\n⚠️ **Stream Error:** ${event.message}"))
                    }
                }
            }

            if (pendingToolCalls.isNotEmpty()) {
                for (call in pendingToolCalls) {
                    when (val audit = ToolCallAuditor.audit(call.name, call.args, toolRegistry)) {
                        is AuditResult.Rejected -> {
                            send(StreamPipelineEvent.TokenDelta("\n\n🛡️ **Guardrail Blocked Tool Call:** ${audit.reason}"))
                            continue
                        }

                        is AuditResult.Valid -> {
                            val tool = toolRegistry.getTool(call.name)!!

                            if (!tool.requiresUserApproval) {
                                val tempSig = "${call.name}(${call.args})"
                                send(StreamPipelineEvent.ReadToolStarted(tempSig))
                                val timed = measureTimedValue { tool.execute(audit.sanitizedArgs) }
                                send(
                                    StreamPipelineEvent.ReadToolCompleted(
                                        signature = tempSig,
                                        durationMs = timed.duration.inWholeMilliseconds.coerceAtLeast(1)
                                    )
                                )

                                streamWords("\n\n${timed.value.humanReadableSummary}. ")
                            } else {
                                val preview = tool.buildApprovalPreview(audit.sanitizedArgs)!!
                                val deferred = CompletableDeferred<Boolean>().also {
                                    pendingApprovalDeferred = it
                                }

                                send(
                                    StreamPipelineEvent.RequestWriteApproval(
                                        PendingWriteCardState(
                                            callId = "call_${UUID.randomUUID().toString().take(4)}",
                                            actionName = preview.actionBadge,
                                            fields = preview.fields
                                        )
                                    )
                                )

                                val userApproved = deferred.await()
                                if (userApproved) {
                                    val result = withContext(NonCancellable) {
                                        tool.execute(audit.sanitizedArgs)
                                    }
                                    streamWords("\n\nSuccess! ${result.humanReadableSummary}.")
                                } else {
                                    streamWords("\n\nCancelled—database write was rejected.")
                                }
                            }
                        }
                    }
                }
            }

            close()
        }

        awaitClose { job.cancel() }
    }

    /**
     * DEMO SIMULATOR ORCHESTRATOR (Offline fallback when Demo Sim pill is selected)
     */
    private fun orchestrateDemoSimTurn(prompt: String, agentMsgId: String): Flow<StreamPipelineEvent> = callbackFlow {
        val job = launch {
            val lower = prompt.lowercase().trim()

            val parsedAmount = Regex("""\$?(\d+(\.\d{1,2})?)""")
                .find(prompt)
                ?.groupValues?.get(1)
                ?.toDoubleOrNull()

            when {
                // 1. Clear entire database
                lower.contains("clear") || lower.contains("wipe") -> {
                    val clearTool = toolRegistry.getTool("clear_all_expenses")!!
                    val args = buildJsonObject {}
                    val preview = clearTool.buildApprovalPreview(args)!!

                    streamWords("Are you sure you want to permanently clear all expenses from your local database?")

                    val deferred = CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }
                    send(
                        StreamPipelineEvent.RequestWriteApproval(
                            PendingWriteCardState(
                                callId = "call_${agentMsgId.take(4)}",
                                actionName = preview.actionBadge,
                                fields = preview.fields
                            )
                        )
                    )

                    if (deferred.await()) {
                        val result = withContext(NonCancellable) {
                            clearTool.execute(args)
                        }
                        streamWords("\n\nDone! ${result.humanReadableSummary}.")
                    } else {
                        streamWords("\n\nCancelled—your database records were kept.")
                    }
                }

                // 2. Delete / Undo last entry
                lower.contains("undo") || lower.contains("delete") || lower.contains("remove") -> {
                    val deleteTool = toolRegistry.getTool("delete_last_expense")!!
                    val args = buildJsonObject {}
                    val preview = deleteTool.buildApprovalPreview(args)!!

                    streamWords("I can remove your most recent expense entry. Please confirm below:")

                    val deferred = CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }
                    send(
                        StreamPipelineEvent.RequestWriteApproval(
                            PendingWriteCardState(
                                callId = "call_${agentMsgId.take(4)}",
                                actionName = preview.actionBadge,
                                fields = preview.fields
                            )
                        )
                    )

                    if (deferred.await()) {
                        val result = withContext(NonCancellable) {
                            deleteTool.execute(args)
                        }
                        streamWords("\n\nDone! ${result.humanReadableSummary} from your local Room database.")
                    } else {
                        streamWords("\n\nCancelled—your most recent expense entry was kept.")
                    }
                }

                // 3. Query / Summary
                lower.contains("summary") || lower.contains("how much") || lower.contains("total") || lower.contains("list") -> {
                    val getTool = toolRegistry.getTool("get_expenses")!!
                    val sig = "get_expenses()"
                    send(StreamPipelineEvent.ReadToolStarted(sig))
                    delay(250)

                    val timed = measureTimedValue { getTool.execute(buildJsonObject {}) }
                    send(StreamPipelineEvent.ReadToolCompleted(sig, timed.duration.inWholeMilliseconds.coerceAtLeast(1)))

                    val total = timed.value.jsonOutput["totalAmountUsd"]?.jsonPrimitive?.double ?: 0.0
                    val count = timed.value.jsonOutput["visitCount"]?.jsonPrimitive?.int ?: 0

                    if (count == 0) {
                        streamWords("Your local SQLite ledger is currently empty (**0 records**, **$0.00**).")
                    } else {
                        streamWords(
                            "Across **$count** recorded transactions in your local SQLite ledger, " +
                                    "your total spending is **$${String.format(Locale.US, "%.2f", total)}**."
                        )
                    }
                }

                // 4. Log an expense
                parsedAmount != null -> {
                    val category = when {
                        lower.contains("coffee") || lower.contains("cafe") -> "Coffee"
                        lower.contains("grocer") || lower.contains("market") -> "Groceries"
                        lower.contains("uber") || lower.contains("taxi") || lower.contains("transit") -> "Transport"
                        lower.contains("dinner") || lower.contains("lunch") || lower.contains("food") -> "Dining Out"
                        else -> "General"
                    }

                    streamWords(
                        "Please confirm the new **$${String.format(Locale.US, "%.2f", parsedAmount)}** **$category** entry before I save it to your ledger:"
                    )

                    val insertTool = toolRegistry.getTool("insert_expense")!!
                    val writeArgs = buildJsonObject {
                        put("amount", parsedAmount)
                        put("category", category)
                        put("merchant", category)
                        put("date", "Today")
                    }

                    val audit = ToolCallAuditor.audit("insert_expense", writeArgs, toolRegistry)
                    if (audit is AuditResult.Valid) {
                        val preview = insertTool.buildApprovalPreview(audit.sanitizedArgs)!!
                        val approvalDeferred = CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }
                        send(
                            StreamPipelineEvent.RequestWriteApproval(
                                PendingWriteCardState(
                                    callId = "call_${agentMsgId.take(4)}",
                                    actionName = preview.actionBadge,
                                    fields = preview.fields
                                )
                            )
                        )

                        if (approvalDeferred.await()) {
                            val writeResult = withContext(NonCancellable) {
                                insertTool.execute(audit.sanitizedArgs)
                            }
                            val rowId = writeResult.jsonOutput["insertedRowId"]?.jsonPrimitive?.long
                            streamWords(
                                "\n\nSaved! Logged **$${String.format(Locale.US, "%.2f", parsedAmount)}** under **$category** (SQLite Row **#ID-$rowId**)."
                            )
                        } else {
                            streamWords("\n\nUnderstood—I discarded the entry and left your database unchanged.")
                        }
                    } else if (audit is AuditResult.Rejected) {
                        streamWords("\n\n🛡️ **Guardrail Blocked:** ${audit.reason}")
                    }
                }

                // 5. Normal conversation / Greetings
                else -> {
                    streamWords(
                        "Hello! I'm **PocketAgent**, your on-device expense assistant. " +
                                "You can ask me to check your spending, log a new expense (e.g., *\"Log $14 for coffee\"*), " +
                                "undo your last entry, or clear the database."
                    )
                }
            }

            _uiState.update { state ->
                state.copy(
                    usedTokens = contextWindowManager.estimateTotalTokens(state.messages)
                )
            }
            close()
        }

        awaitClose { job.cancel() }
    }

    private suspend fun ProducerScope<StreamPipelineEvent>.streamWords(text: String) {
        val tokens = text.split(" ")
        tokens.forEachIndexed { index, word ->
            delay(38)
            val prefix = if (index == 0) "" else " "
            send(StreamPipelineEvent.TokenDelta("$prefix$word"))
        }
    }

    private fun updateAgentMessage(
        messageId: String,
        transform: (ChatMessageUiModel.AgentMessage) -> ChatMessageUiModel.AgentMessage
    ) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg is ChatMessageUiModel.AgentMessage && msg.id == messageId) {
                        transform(msg)
                    } else msg
                }
            )
        }
    }

    override fun onCleared() {
        activeGenerationJob?.cancel()
        pendingApprovalDeferred?.cancel()
        pendingApprovalDeferred = null
    }

    companion object {
        fun provideFactory(
            expenseDao: ExpenseDao,
            toolRegistry: AgentToolRegistry
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(expenseDao, toolRegistry) as T
            }
        }
    }
}
