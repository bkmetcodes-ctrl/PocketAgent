package com.bekacodes.pocketagent.ui.presentation.chat.model

enum class MessageStreamStatus {
    Streaming,
    AwaitingApproval,
    Completed,
    InterruptedByUser,
    Error
}