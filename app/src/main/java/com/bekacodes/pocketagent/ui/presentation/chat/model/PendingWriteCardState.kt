package com.bekacodes.pocketagent.ui.presentation.chat.model

import androidx.compose.runtime.Immutable
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution

@Immutable
data class PendingWriteCardState(
    val callId: String,
    val actionName: String,    // e.g., "INSERT_EXPENSE"
    val fields: List<Pair<String, String>>, // ("Amount" to "$28.00"), ("Category" to "Dining Out")...
    val resolution: ApprovalResolution = ApprovalResolution.PENDING
)