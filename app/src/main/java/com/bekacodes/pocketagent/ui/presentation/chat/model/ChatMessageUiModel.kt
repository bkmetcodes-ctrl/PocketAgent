package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable
import com.bekacodes.pocketagent.ui.presentation.chat.components.ReadToolBadge

@Immutable
sealed interface ChatMessageUiModel {
    val id: String
    val timestamp: String

    data class UserMessage(
        override val id: String,
        override val timestamp: String,
        val text: String
    ) : ChatMessageUiModel

    data class AgentMessage(
        override val id: String,
        override val timestamp: String,
        val executedReadTools: List<ReadToolBadge> = emptyList(),
        val markdownText: String = "",
        val pendingWriteApproval: PendingWriteCardState? = null,
        val status: MessageStreamStatus = MessageStreamStatus.Completed
    ) : ChatMessageUiModel
}