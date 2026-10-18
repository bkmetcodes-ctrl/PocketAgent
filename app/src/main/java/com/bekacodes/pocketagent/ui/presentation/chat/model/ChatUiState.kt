package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable
import com.bekacodes.pocketagent.ui.presentation.EngineMode

@Immutable
data class ChatUiState(
    val engineMode: EngineMode = EngineMode.DEMO_SIM,
    val usedTokens: Int = 1420,
    val maxTokens: Int = 8000,
    val messages: List<ChatMessageUiModel> = emptyList(),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val quickSuggestions: List<String> = listOf("Monthly summary", "Undo last entry")
)