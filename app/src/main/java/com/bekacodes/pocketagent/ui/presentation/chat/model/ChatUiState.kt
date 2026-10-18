package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable
import com.bekacodes.pocketagent.data.ExpenseEntity
import com.bekacodes.pocketagent.ui.presentation.EngineMode

@Immutable
data class ChatUiState(
    val engineMode: EngineMode = EngineMode.DEMO_SIM,
    val usedTokens: Int = 0,
    val maxTokens: Int = 8000,
    val messages: List<ChatMessageUiModel> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val isInspectorVisible: Boolean = false,
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val quickSuggestions: List<String> = listOf(
        "Monthly summary",
        "Undo last entry",
        "Clear all data"
    )
)