package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable

/**
 * UI badge state for executed read-only database queries.
 */
@Immutable
data class ReadToolBadge(
    val callSignature: String, // e.g., "get_expenses(category='Coffee')"
    val durationMs: Long,
    val isRunning: Boolean = false
)