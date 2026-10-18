package com.bekacodes.pocketagent.domain

import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel

/**
 * Manages the on-device context window budget using a heuristic token estimator
 * (~4 characters per token for English/JSON) and sliding-window turn eviction.
 */
class ContextWindowManager(
    val maxContextTokens: Int = 8000,
    private val systemPromptOverheadTokens: Int = 420
) {
    fun estimateTotalTokens(messages: List<ChatMessageUiModel>): Int {
        val messageTokens = messages.sumOf { msg ->
            when (msg) {
                is ChatMessageUiModel.UserMessage -> estimateTextTokens(msg.text)
                is ChatMessageUiModel.AgentMessage -> {
                    val textTokens = estimateTextTokens(msg.markdownText)
                    val toolTokens = msg.executedReadTools.size * 45
                    val cardTokens = if (msg.pendingWriteApproval != null) 65 else 0
                    textTokens + toolTokens + cardTokens
                }
            }
        }
        return (systemPromptOverheadTokens + messageTokens).coerceAtMost(maxContextTokens)
    }

    /**
     * Prunes oldest User/Agent message pairs first when approaching 85% of maxContextTokens,
     * preserving the most recent turns so active tool approvals never lose context.
     */
    fun trimToFitBudget(messages: List<ChatMessageUiModel>): List<ChatMessageUiModel> {
        val safeThreshold = (maxContextTokens * 0.85).toInt()
        val mutable = messages.toMutableList()

        while (mutable.size > 2 && estimateTotalTokens(mutable) > safeThreshold) {
            mutable.removeAt(0)
        }
        return mutable
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / 4).coerceAtLeast(1)
    }
}