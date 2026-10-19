package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable

/**
 * UI model representing chat items rendered inside the main chat list.
 */
@Immutable
sealed interface ChatMessageUiModel {
    val id: String
    val timestamp: String

    @Immutable
    data class UserMessage(
        override val id: String,
        override val timestamp: String,
        val text: String
    ) : ChatMessageUiModel

    @Immutable
    data class AgentMessage(
        override val id: String,
        override val timestamp: String,
        val executedReadTools: List<ReadToolBadge> = emptyList(),
        val markdownText: String = "",
        val pendingWriteApproval: PendingWriteCardState? = null,
        val status: MessageStreamStatus = MessageStreamStatus.Completed
    ) : ChatMessageUiModel
}
