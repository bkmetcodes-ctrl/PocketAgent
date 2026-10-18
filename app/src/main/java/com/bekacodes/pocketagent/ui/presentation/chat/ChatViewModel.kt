package com.bekacodes.pocketagent.ui.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import com.bekacodes.pocketagent.ui.presentation.chat.components.ReadToolBadge
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatUiState
import com.bekacodes.pocketagent.ui.presentation.chat.model.PendingWriteCardState
import com.bekacodes.pocketagent.ui.presentation.chat.model.SimulatedStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds


class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeGenerationJob: Job? = null

    // Holds the suspended coroutine signal while waiting for the user to tap Accept or Reject
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    init {
        // Seed initial conversation so the screen launches with the Figma demo ready to test
        onIntent(
            ChatIntent.SendPrompt(
                "How much did I spend on coffee this month, and log a $28 dinner from last night."
            )
        )
    }

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> {
                _uiState.update { it.copy(inputText = intent.text) }
            }

            is ChatIntent.SwitchEngineMode -> {
                _uiState.update { it.copy(engineMode = intent.mode) }
            }

            is ChatIntent.SendPrompt -> {
                startStreamingTurn(intent.prompt)
            }

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
                streamAgentTurn(prompt, agentMsgId)
                    .scan(initialAgentMessage) { acc, event ->
                        when (event) {
                            is SimulatedStreamEvent.ReadToolStarted -> acc.copy(
                                executedReadTools = acc.executedReadTools + ReadToolBadge(
                                    callSignature = event.signature,
                                    durationMs = 0,
                                    isRunning = true
                                )
                            )

                            is SimulatedStreamEvent.ReadToolCompleted -> acc.copy(
                                executedReadTools = acc.executedReadTools.map {
                                    if (it.callSignature == event.signature) {
                                        it.copy(durationMs = event.durationMs, isRunning = false)
                                    } else it
                                }
                            )

                            is SimulatedStreamEvent.TokenDelta -> acc.copy(
                                markdownText = acc.markdownText + event.delta,
                                status = MessageStreamStatus.Streaming
                            )

                            is SimulatedStreamEvent.RequestWriteApproval -> acc.copy(
                                pendingWriteApproval = event.card,
                                status = MessageStreamStatus.AwaitingApproval
                            )

                            is SimulatedStreamEvent.TokensConsumed -> acc
                        }
                    }
                    .flowOn(Dispatchers.Default)
                    .collect { updatedAgentMsg ->
                        updateAgentMessage(agentMsgId) { updatedAgentMsg }
                    }

                // Mark stream completed when flow finishes normally
                updateAgentMessage(agentMsgId) { it.copy(status = MessageStreamStatus.Completed) }
            } catch (e: CancellationException) {
                // Deterministic cleanup when the user taps Stop Generation
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
        // Resume the suspended coroutine inside streamAgentTurn!
        pendingApprovalDeferred?.complete(approved)
    }

    /**
     * Simulates an SSE stream with mid-stream tool execution and Human-in-the-Loop suspension.
     */
    private fun streamAgentTurn(prompt: String, agentMsgId: String): Flow<SimulatedStreamEvent> =
        callbackFlow {
            val job = launch {
                // 1. Simulate read-only Room DB tool execution
                val toolSig = "get_expenses(category='Coffee')"
                send(SimulatedStreamEvent.ReadToolStarted(toolSig))
                delay(450.milliseconds)
                send(SimulatedStreamEvent.ReadToolCompleted(toolSig, durationMs = 14))

                // 2. Stream first part of markdown tokens at ~50 tokens/sec
                val introTokens = listOf(
                    "You've ", "spent ", "**$42.50** ", "across ", "9 ", "coffee ",
                    "visits ", "in ", "October. ", "Before ", "I ", "update ",
                    "your ", "ledger, ", "please ", "confirm ", "the ", "new ",
                    "dinner ", "entry:"
                )
                for (token in introTokens) {
                    delay(45.milliseconds)
                    send(SimulatedStreamEvent.TokenDelta(token))
                }

                // 3. Emit the Pending Database Write card and SUSPEND waiting for user decision
                val approvalDeferred = CompletableDeferred<Boolean>()
                pendingApprovalDeferred = approvalDeferred

                send(
                    SimulatedStreamEvent.RequestWriteApproval(
                        PendingWriteCardState(
                            callId = "call_${agentMsgId.take(4)}",
                            actionName = "INSERT_EXPENSE",
                            fields = listOf(
                                "Amount" to "$28.00",
                                "Category" to "Dining Out",
                                "Date" to "Yesterday"
                            )
                        )
                    )
                )

                // Coroutine pauses right here until the user taps "Accept & Save" or "Reject"
                val userApproved = approvalDeferred.await()

                // 4. Stream the agent's follow-up response based on the user's decision
                val followUpTokens = if (userApproved) {
                    listOf(
                        "\n\nDone! ", "I've ", "logged ", "**$28.00** ", "under ",
                        "**Dining Out** ", "in ", "your ", "local ", "database."
                    )
                } else {
                    listOf(
                        "\n\nUnderstood—", "I ", "cancelled ", "the ", "**$28.00** ",
                        "entry ", "and ", "left ", "your ", "ledger ", "unchanged."
                    )
                }

                for (token in followUpTokens) {
                    delay(40.milliseconds)
                    send(SimulatedStreamEvent.TokenDelta(token))
                }

                _uiState.update { it.copy(usedTokens = (it.usedTokens + 185).coerceAtMost(it.maxTokens)) }
                close()
            }

            awaitClose {
                // Guarantees socket/stream cancellation when StopGeneration is clicked
                job.cancel()
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
}