package com.bekacodes.pocketagent.ui.presentation.chat.model

/**
 * Status lifecycle of an Agent streaming message turn.
 */
enum class MessageStreamStatus {
    Streaming,
    AwaitingApproval,
    Completed,
    InterruptedByUser,
    Error
}
