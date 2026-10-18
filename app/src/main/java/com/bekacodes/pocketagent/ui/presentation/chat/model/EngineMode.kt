package com.bekacodes.pocketagent.ui.presentation.chat.model

/**
 * Execution engine mode for PocketAgent.
 */
enum class EngineMode {
    /** Offline simulated agent engine for demos and offline use. */
    DEMO_SIM,

    /** Live streaming Gemini 3.8 Flash model connected over SSE. */
    GEMINI_LIVE
}
