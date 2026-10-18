package com.bekacodes.pocketagent.ui.presentation.chat.model


sealed interface SimulatedStreamEvent {
    data class ReadToolStarted(val signature: String) : SimulatedStreamEvent
    data class ReadToolCompleted(val signature: String, val durationMs: Long) : SimulatedStreamEvent
    data class TokenDelta(val delta: String) : SimulatedStreamEvent
    data class RequestWriteApproval(val card: PendingWriteCardState) : SimulatedStreamEvent
    data class TokensConsumed(val count: Int) : SimulatedStreamEvent
}