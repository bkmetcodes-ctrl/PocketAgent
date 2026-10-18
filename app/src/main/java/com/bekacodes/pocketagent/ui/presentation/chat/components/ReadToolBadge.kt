package com.bekacodes.pocketagent.ui.presentation.chat.components

import androidx.compose.runtime.Immutable

@Immutable
data class ReadToolBadge(
    val callSignature: String, // e.g., "get_expenses(category='Coffee')"
    val durationMs: Long,
    val isRunning: Boolean = false
)