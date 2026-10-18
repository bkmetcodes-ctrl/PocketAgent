package com.bekacodes.pocketagent.ui.presentation.chat.model

/**
 * User intents and actions dispatched from UI components to [com.bekacodes.pocketagent.ui.presentation.chat.ChatViewModel].
 */
sealed interface ChatIntent {
    /** Updates user text field input. */
    data class UpdateInput(val text: String) : ChatIntent

    /** Submits user prompt to start model streaming turn. */
    data class SendPrompt(val prompt: String) : ChatIntent

    /** Immediately aborts active model stream generation. */
    data object StopGeneration : ChatIntent

    /** Switches between Demo Sim and Live Gemini modes. */
    data class SwitchEngineMode(val mode: EngineMode) : ChatIntent

    /** Resolves pending database write card approval or rejection. */
    data class ResolveWriteApproval(
        val messageId: String,
        val callId: String,
        val approved: Boolean
    ) : ChatIntent

    /** Toggles SQLite database inspector bottom sheet visibility. */
    data class ToggleInspector(val visible: Boolean) : ChatIntent

    /** Clears all database records from SQLite ledger. */
    data object ClearDatabase : ChatIntent
}
