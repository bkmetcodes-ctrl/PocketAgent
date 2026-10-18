package com.bekacodes.pocketagent.ui.presentation.chat.model


sealed interface StreamPipelineEvent {
    data class ReadToolStarted(val signature: String) : StreamPipelineEvent
    data class ReadToolCompleted(val signature: String, val durationMs: Long) : StreamPipelineEvent
    data class TokenDelta(val delta: String) : StreamPipelineEvent
    data class RequestWriteApproval(val card: PendingWriteCardState) : StreamPipelineEvent
}