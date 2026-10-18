package com.bekacodes.pocketagent.ui.presentation.chat.model

import com.bekacodes.pocketagent.ui.presentation.EngineMode

sealed interface ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent
    data class SendPrompt(val prompt: String) : ChatIntent
    data object StopGeneration : ChatIntent
    data class SwitchEngineMode(val mode: EngineMode) : ChatIntent
    data class ResolveWriteApproval(
        val messageId: String,
        val callId: String,
        val approved: Boolean
    ) : ChatIntent
    data class ToggleInspector(val visible: Boolean) : ChatIntent
    data object ResetDemoDatabase : ChatIntent
}