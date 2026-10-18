package com.bekacodes.pocketagent.ui.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bekacodes.pocketagent.BuildConfig
import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.GeminiSseClient
import com.bekacodes.pocketagent.data.LlmStreamEvent
import com.bekacodes.pocketagent.data.ResetDemoLedger
import com.bekacodes.pocketagent.data.seedDemoDataIfEmpty
import com.bekacodes.pocketagent.domain.AgentToolRegistry
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution
import com.bekacodes.pocketagent.ui.presentation.EngineMode
import com.bekacodes.pocketagent.ui.presentation.chat.components.ReadToolBadge
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatUiState
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import com.bekacodes.pocketagent.ui.presentation.chat.model.PendingWriteCardState
import com.bekacodes.pocketagent.ui.presentation.chat.model.StreamPipelineEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
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
            expenseDao.seedDemoDataIfEmpty()

            launch {
                expenseDao.observeAllExpenses().collect { rows ->
                    _uiState.update { it.copy(expenses = rows) }
                }
            }

            startStreamingTurn(
                "How much did I spend on coffee this month, and log a $28 dinner from last night."
            )
        }
    }

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> _uiState.update { it.copy(inputText = intent.text) }
            is ChatIntent.SwitchEngineMode -> _uiState.update { it.copy(engineMode = intent.mode) }
            is ChatIntent.ToggleInspector -> _uiState.update { it.copy(isInspectorVisible = intent.visible) }
            is ChatIntent.ResetDemoDatabase -> {
                viewModelScope.launch { ResetDemoLedger(expenseDao) }
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
                    val tool = toolRegistry.getTool(call.name) ?: continue

                    if (!tool.requiresUserApproval) {
                        // Read-only tool: execute immediately against Room and show green chip
                        val tempSig = "${call.name}(${call.args})"
                        send(StreamPipelineEvent.ReadToolStarted(tempSig))
                        val timed = measureTimedValue { tool.execute(call.args) }
                        send(
                            StreamPipelineEvent.ReadToolCompleted(
                                signature = tempSig,
                                durationMs = timed.duration.inWholeMilliseconds.coerceAtLeast(1)
                            )
                        )

                        // Stream a helpful summary of the tool result back to the user
                        streamWords("\n\n${timed.value.humanReadableSummary}. ")
                    } else {
                        // Write tool: build preview card and SUSPEND coroutine until user taps Accept/Reject
                        val preview = tool.buildApprovalPreview(call.args)!!
                        val deferred = CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }

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
    private fun orchestrateDemoSimTurn(
        prompt: String,
        agentMsgId: String
    ): Flow<StreamPipelineEvent> = callbackFlow {
        val job = launch {
            val lower = prompt.lowercase()
            when {
                lower.contains("undo") || lower.contains("delete") -> {
                    val deleteTool = toolRegistry.getTool("delete_last_expense")!!
                    val args = buildJsonObject {}
                    val preview = deleteTool.buildApprovalPreview(args)!!

                    streamWords("I can remove your most recent expense entry. Please confirm below:")

                    val deferred =
                        CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }
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

                lower.contains("summary") -> {
                    val getTool = toolRegistry.getTool("get_expenses")!!
                    val sig = "get_expenses()"
                    send(StreamPipelineEvent.ReadToolStarted(sig))
                    delay(250)

                    val timed = measureTimedValue { getTool.execute(buildJsonObject {}) }
                    send(
                        StreamPipelineEvent.ReadToolCompleted(
                            sig,
                            timed.duration.inWholeMilliseconds.coerceAtLeast(1)
                        )
                    )

                    val total =
                        timed.value.jsonOutput["totalAmountUsd"]?.jsonPrimitive?.double ?: 0.0
                    val count = timed.value.jsonOutput["visitCount"]?.jsonPrimitive?.int ?: 0

                    streamWords(
                        "Across **$count** recorded transactions in your local SQLite ledger, " +
                                "your total spending this month is **$${
                                    String.format(
                                        "%.2f",
                                        total
                                    )
                                }**."
                    )
                }

                else -> {
                    val getTool = toolRegistry.getTool("get_expenses")!!
                    val readArgs = buildJsonObject { put("category", "Coffee") }
                    val sig = "get_expenses(category='Coffee')"
                    send(StreamPipelineEvent.ReadToolStarted(sig))
                    delay(300)

                    val timedRead = measureTimedValue { getTool.execute(readArgs) }
                    send(
                        StreamPipelineEvent.ReadToolCompleted(
                            signature = sig,
                            durationMs = timedRead.duration.inWholeMilliseconds.coerceAtLeast(1)
                        )
                    )

                    val coffeeTotal =
                        timedRead.value.jsonOutput["totalAmountUsd"]?.jsonPrimitive?.double ?: 0.0
                    val coffeeVisits =
                        timedRead.value.jsonOutput["visitCount"]?.jsonPrimitive?.int ?: 0

                    streamWords(
                        "You've spent **$${
                            String.format(
                                "%.2f",
                                coffeeTotal
                            )
                        }** across $coffeeVisits coffee visits in October. Before I update your ledger, please confirm the new dinner entry:"
                    )

                    val insertTool = toolRegistry.getTool("insert_expense")!!
                    val writeArgs = buildJsonObject {
                        put("amount", 28.00)
                        put("category", "Dining Out")
                        put("merchant", "Local Bistro")
                        put("date", "Yesterday")
                    }
                    val preview = insertTool.buildApprovalPreview(writeArgs)!!

                    val approvalDeferred =
                        CompletableDeferred<Boolean>().also { pendingApprovalDeferred = it }
                    send(
                        StreamPipelineEvent.RequestWriteApproval(
                            PendingWriteCardState(
                                callId = "call_${agentMsgId.take(4)}",
                                actionName = preview.actionBadge,
                                fields = preview.fields
                            )
                        )
                    )

                    val approved = approvalDeferred.await()
                    if (approved) {
                        val writeResult = insertTool.execute(writeArgs)
                        val rowId = writeResult.jsonOutput["insertedRowId"]?.jsonPrimitive?.long
                        streamWords(
                            "\n\nSaved! Logged **$28.00** under **Dining Out** (SQLite Row **#ID-$rowId**). " +
                                    "Tap the token counter in the top bar to inspect your live database."
                        )
                    } else {
                        streamWords("\n\nUnderstood—I discarded the **$28.00** dinner entry and left your database unchanged.")
                    }
                }
            }

            _uiState.update { it.copy(usedTokens = (it.usedTokens + 180).coerceAtMost(it.maxTokens)) }
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