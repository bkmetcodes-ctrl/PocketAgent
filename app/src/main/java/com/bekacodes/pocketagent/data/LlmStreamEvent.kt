package com.bekacodes.pocketagent.data

import kotlinx.serialization.json.JsonObject

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class FunctionCallRequested(val name: String, val args: JsonObject) : LlmStreamEvent
    data class UsageMetadata(val totalTokens: Int) : LlmStreamEvent
    data class StreamError(val message: String) : LlmStreamEvent
}