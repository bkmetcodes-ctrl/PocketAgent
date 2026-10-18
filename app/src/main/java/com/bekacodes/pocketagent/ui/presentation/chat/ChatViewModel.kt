package com.bekacodes.pocketagent.ui.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.ResetDemoLedger
import com.bekacodes.pocketagent.data.seedDemoDataIfEmpty
import com.bekacodes.pocketagent.domain.AgentToolRegistry
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.measureTimedValue

class ChatViewModel(
    private val expenseDao: ExpenseDao,
    private val toolRegistry: AgentToolRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch {
            expenseDao.seedDemoDataIfEmpty()

            // Observe live SQLite table for the Database Inspector Sheet
            launch {
                expenseDao.observeAllExpenses().collect { rows ->
                    _uiState.update { it.copy(expenses = rows) }
                }
            }

            // Run the flagship prompt against the real Room database on startup
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
                orchestrateTurn(prompt, agentMsgId)
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
     * Executes real MCP Tools against Room and streams responses based on live SQLite results.
     */
    private fun orchestrateTurn(prompt: String, agentMsgId: String): Flow<StreamPipelineEvent> =
        callbackFlow {
            val job = launch {
                val lower = prompt.lowercase()

                when {
                    // Case 1: User asks to undo the last entry
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

                    // Case 2: Monthly summary query
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

                    // Case 3: Flagship multi-step turn (Read Coffee DB + Propose $28 Dinner Write)
                    else -> {
                        val getTool = toolRegistry.getTool("get_expenses")!!
                        val readArgs = buildJsonObject { put("category", "Coffee") }
                        val sig = "get_expenses(category='Coffee')"

                        send(StreamPipelineEvent.ReadToolStarted(sig))
                        delay(300) // Brief visual beat so the user sees the active tool state
                        val timedRead = measureTimedValue { getTool.execute(readArgs) }
                        send(
                            StreamPipelineEvent.ReadToolCompleted(
                                signature = timedRead.value.signatureDisplay,
                                durationMs = timedRead.duration.inWholeMilliseconds.coerceAtLeast(1)
                            )
                        )

                        val coffeeTotal =
                            timedRead.value.jsonOutput["totalAmountUsd"]?.jsonPrimitive?.double
                                ?: 0.0
                        val coffeeVisits =
                            timedRead.value.jsonOutput["visitCount"]?.jsonPrimitive?.int ?: 0

                        streamWords(
                            "You've spent **$${
                                String.format(
                                    "%.2f",
                                    coffeeTotal
                                )
                            }** across $coffeeVisits coffee " +
                                    "visits in October. Before I update your ledger, please confirm the new dinner entry:"
                        )

                        // Trigger Write Tool with Human-in-the-Loop suspension
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

                        // Suspend coroutine until user taps Accept & Save or Reject
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