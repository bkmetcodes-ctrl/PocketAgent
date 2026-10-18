package com.bekacodes.pocketagent.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject

/**
 * Domain events emitted by [GeminiSseClient] during SSE content generation.
 */
@Immutable
sealed interface LlmStreamEvent {
    /** Incremental text chunk streamed from the model. */
    @Immutable
    data class TextDelta(val text: String) : LlmStreamEvent

    /** Model requested execution of a registered function/tool. */
    @Immutable
    data class FunctionCallRequested(val name: String, val args: JsonObject) : LlmStreamEvent

    /** Token consumption metadata reported by Gemini API. */
    @Immutable
    data class UsageMetadata(val totalTokens: Int) : LlmStreamEvent

    /** Terminal stream error encountered during network transfer or JSON parsing. */
    @Immutable
    data class StreamError(val message: String) : LlmStreamEvent
}
