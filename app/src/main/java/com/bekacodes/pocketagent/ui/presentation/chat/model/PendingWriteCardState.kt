package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable

/**
 * State model for the pending write approval card in agent messages.
 */
@Immutable
data class PendingWriteCardState(
    val callId: String,
    val actionName: String,    // e.g., "INSERT_EXPENSE"
    val fields: List<Pair<String, String>>, // ("Amount" to "$28.00"), ("Category" to "Dining Out")...
    val resolution: ApprovalResolution = ApprovalResolution.PENDING
)
