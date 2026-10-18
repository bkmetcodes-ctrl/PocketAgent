package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable

/**
 * Pipeline events emitted internally during agent stream orchestration.
 */
@Immutable
sealed interface StreamPipelineEvent {
    @Immutable
    data class ReadToolStarted(val signature: String) : StreamPipelineEvent

    @Immutable
    data class ReadToolCompleted(val signature: String, val durationMs: Long) : StreamPipelineEvent

    @Immutable
    data class TokenDelta(val delta: String) : StreamPipelineEvent

    @Immutable
    data class RequestWriteApproval(val card: PendingWriteCardState) : StreamPipelineEvent
}
