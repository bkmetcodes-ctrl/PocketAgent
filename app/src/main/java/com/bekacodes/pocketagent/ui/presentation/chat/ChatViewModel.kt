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
import com.bekacodes.pocketagent.domain.ToolCallAuditor
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution
import com.bekacodes.pocketagent.ui.presentation.EngineMode
import com.bekacodes.pocketagent.ui.presentation.chat.components.ReadToolBadge
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatUiState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.time.measureTimedValue

class ChatViewModel(
    private val expenseDao: ExpenseDao,
    private val toolRegistry: AgentToolRegistry,
    private val geminiSseClient: GeminiSseClient = GeminiSseClient(BuildConfig.GEMINI_API_KEY)
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            // Automatically default to GEMINI_LIVE if an API key is configured!
            engineMode = if (BuildConfig.GEMINI_API_KEY.isNotBlank()) EngineMode.GEMINI_LIVE else EngineMode.DEMO_SIM
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    // Maintains rolling multi-turn conversation history for Gemini Live mode
    private val conversationHistory = mutableListOf<JsonObject>()

    init {
        viewModelScope.launch {
            expenseDao.observeAllExpenses().collect { rows ->
                _uiState.update { it.copy(expenses = rows) }
            }
        }
    }

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> _uiState.update { it.copy(inputText = intent.text) }
            is ChatIntent.SwitchEngineMode -> _uiState.update { it.copy(engineMode = intent.mode) }
            is ChatIntent.ToggleInspector -> _uiState.update { it.copy(isInspectorVisible = intent.visible) }
            is ChatIntent.ClearDatabase -> {
                viewModelScope.launch {
                    expenseDao.clearAll()
                }
            }
            is ChatIntent.SendPrompt -> startStreamingTurn(intent.prompt)
            is ChatIntent.StopGeneration -> {
                activeGenerationJob?.cancel()
                pendingApprovalDeferred?.cancel()
            }
            is ChatIntent.ResolveWriteApproval -> {
                resolveWriteApproval(intent.messageId, intent.approved)
            }
        }
    }

    private fun startStreamingTurn(prompt: String) {
        activeGenerationJob?.cancel()

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

        _uiState.update { state ->
            state.copy(
                inputText = "",
                isGenerating = true,
                messages = state.messages + userMessage + initialAgentMessage
            )
        }

        activeGenerationJob = viewModelScope.launch {
            try {
                val mode = _uiState.value.engineMode
                val stream = if (mode == EngineMode.GEMINI_LIVE) {
                    orchestrateLiveGeminiTurn(prompt, agentMsgId)
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
                    _uiState.update { it.copy(isGenerating = false) }
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
        prompt: String,
        agentMsgId: String
    ): Flow<StreamPipelineEvent> = callbackFlow {
        val job = launch {
            val pendingToolCalls = mutableListOf<LlmStreamEvent.FunctionCallRequested>()
            val streamedTextThisHop = StringBuilder()

            // 1. Stream initial response from Gemini using the streamlined single-turn method
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

            // 2. If Gemini requested tool execution(s), handle them now
            if (pendingToolCalls.isNotEmpty()) {
                for (call in pendingToolCalls) {
                    when (val audit = ToolCallAuditor.audit(call.name, call.args, toolRegistry)) {
                        is AuditResult.Rejected -> {
                            send(StreamPipelineEvent.TokenDelta("\n\n🛡️ **Guardrail Blocked Tool Call:** ${audit.reason}"))
                            continue
                        }

                        is AuditResult.Valid -> {
                            val tool = toolRegistry.getTool(call.name)!!
                            // Proceed with !tool.requiresUserApproval / write approval card...


                            if (!tool.requiresUserApproval) {
                                // Read-only tool: execute immediately against Room and show green chip
                                val tempSig = "${call.name}(${call.args})"
                                send(StreamPipelineEvent.ReadToolStarted(tempSig))
                                val timed = measureTimedValue { tool.execute(call.args) }
                                send(
                                    StreamPipelineEvent.ReadToolCompleted(
                                        signature = tempSig,
                                        durationMs = timed.duration.inWholeMilliseconds.coerceAtLeast(
                                            1
                                        )
                                    )
                                )

                                // Stream a helpful summary of the tool result back to the user
                                streamWords("\n\n${timed.value.humanReadableSummary}. ")
                            } else {
                                // Write tool: build preview card and SUSPEND coroutine until user taps Accept/Reject
                                val preview = tool.buildApprovalPreview(call.args)!!
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
                                    val result = tool.execute(call.args)
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

    private fun buildFunctionResponsePart(name: String, response: JsonObject): JsonObject =
        buildJsonObject {
            putJsonObject("functionResponse") {
                put("name", name)
                put("response", response)
            }
        }

    /**
     * DEMO SIMULATOR ORCHESTRATOR (Offline fallback when Demo Sim pill is selected)
     */
    private fun orchestrateDemoSimTurn(prompt: String, agentMsgId: String): Flow<StreamPipelineEvent> = callbackFlow {
        val job = launch {
            val lower = prompt.lowercase().trim()

            // Only extract an amount if the user actually typed a number
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
                        val result = clearTool.execute(args)
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
                        val result = deleteTool.execute(args)
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
                                    "your total spending is **$${String.format("%.2f", total)}**."
                        )
                    }
                }

                // 4. Log an expense — ONLY if the user actually provided a number in their prompt
                parsedAmount != null -> {
                    val category = when {
                        lower.contains("coffee") || lower.contains("cafe") -> "Coffee"
                        lower.contains("grocer") || lower.contains("market") -> "Groceries"
                        lower.contains("uber") || lower.contains("taxi") || lower.contains("transit") -> "Transport"
                        lower.contains("dinner") || lower.contains("lunch") || lower.contains("food") -> "Dining Out"
                        else -> "General"
                    }

                    streamWords(
                        "Please confirm the new **$${String.format("%.2f", parsedAmount)}** **$category** entry before I save it to your ledger:"
                    )

                    val insertTool = toolRegistry.getTool("insert_expense")!!
                    val writeArgs = buildJsonObject {
                        put("amount", parsedAmount)
                        put("category", category)
                        put("merchant", category)
                        put("date", "Today")
                    }
                    val preview = insertTool.buildApprovalPreview(writeArgs)!!

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
                        val writeResult = insertTool.execute(writeArgs)
                        val rowId = writeResult.jsonOutput["insertedRowId"]?.jsonPrimitive?.long
                        streamWords(
                            "\n\nSaved! Logged **$${String.format("%.2f", parsedAmount)}** under **$category** (SQLite Row **#ID-$rowId**)."
                        )
                    } else {
                        streamWords("\n\nUnderstood—I discarded the entry and left your database unchanged.")
                    }
                }

                // 5. Normal conversation / Greetings ("hello", "hi", "what can you do?")
                else -> {
                    streamWords(
                        "Hello! I'm **PocketAgent**, your on-device expense assistant. " +
                                "You can ask me to check your spending, log a new expense (e.g., *\"Log $14 for coffee\"*), " +
                                "undo your last entry, or clear the database."
                    )
                }
            }

            _uiState.update { it.copy(usedTokens = (it.usedTokens + 95).coerceAtMost(it.maxTokens)) }
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