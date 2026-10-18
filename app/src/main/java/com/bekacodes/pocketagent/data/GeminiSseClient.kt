package com.bekacodes.pocketagent.data

import com.bekacodes.pocketagent.domain.AgentTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Thread-safe client that streams Gemini turn responses over Server-Sent Events (SSE).
 *
 * Emits incremental [LlmStreamEvent] instances. Cancelling the collecting coroutine
 * deterministically aborts the underlying HTTP SSE socket via [awaitClose].
 *
 * @param apiKey Gemini REST API key.
 * @param okHttpClient Shared or custom OkHttpClient instance configured with appropriate timeouts.
 */
class GeminiSseClient(
    private val apiKey: String,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams a single Gemini turn over Server-Sent Events (SSE).
     * Automatically dispatches network I/O on [Dispatchers.IO].
     */
    fun streamTurn(
        prompt: String,
        tools: List<AgentTool>
    ): Flow<LlmStreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(
                LlmStreamEvent.StreamError(
                    "Missing GEMINI_API_KEY in local.properties. Switch to 'Demo Sim' or add your API key."
                )
            )
            close()
            return@callbackFlow
        }

        val requestPayload = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject {
                        put(
                            "text",
                            "You are PocketAgent, an on-device financial assistant connected to a local SQLite database. " +
                                    "Only call tools when the user explicitly asks to check expenses (`get_expenses`), " +
                                    "log a specific expense (`insert_expense`), delete the last entry (`delete_last_expense`), " +
                                    "or clear the database (`clear_all_expenses`). " +
                                    "Never invent or guess dollar amounts. If the user says hello or asks a general question, " +
                                    "reply conversationally without calling any tools."
                        )
                    }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", json.parseToJsonElement(tool.parametersSchemaJson))
                                }
                            }
                        }
                    }
                }
            }
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse&key=$apiKey"

        val request = try {
            Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            trySend(LlmStreamEvent.StreamError("Request construction failed: ${e.localizedMessage}"))
            close()
            return@callbackFlow
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject

                    root["usageMetadata"]?.jsonObject?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.let { total ->
                        trySend(LlmStreamEvent.UsageMetadata(total))
                    }

                    val parts = root["candidates"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray ?: return

                    for (partElement in parts) {
                        val part = partElement.jsonObject

                        part["text"]?.jsonPrimitive?.contentOrNull?.let { textChunk ->
                            if (textChunk.isNotEmpty()) {
                                trySend(LlmStreamEvent.TextDelta(textChunk))
                            }
                        }

                        part["functionCall"]?.jsonObject?.let { fnCall ->
                            val fnName = fnCall["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                            val fnArgs = fnCall["args"]?.jsonObject ?: buildJsonObject {}
                            trySend(LlmStreamEvent.FunctionCallRequested(fnName, fnArgs))
                        }
                    }
                }.onFailure { t ->
                    trySend(LlmStreamEvent.StreamError("Event parsing error: ${t.localizedMessage}"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorBody = try {
                    response?.body?.use { it.string().take(200) }
                } catch (_: Exception) {
                    null
                }
                val msg = t?.localizedMessage
                    ?: "HTTP ${response?.code ?: "Unknown"}: ${errorBody ?: "Stream failed"}"
                trySend(LlmStreamEvent.StreamError(msg))
                close()
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
