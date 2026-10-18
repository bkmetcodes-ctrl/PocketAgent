# PocketAgent ✦ — Agentic Android Architecture Showcase

An AI-first personal finance assistant built with **Kotlin**, **Jetpack Compose**, **Clean Architecture (MVI)**, and **Room**. 

Instead of acting as a passive text chatbot, **PocketAgent** demonstrates **on-device MCP (Model Context Protocol) tool calling**, **lossless SSE token streaming**, **deterministic hallucination guardrails**, and **Human-in-the-Loop (HITL) coroutine suspension** for mutating database operations.

---

## ✨ Key Architectural Highlights

1. **Lossless SSE Token Streaming (`callbackFlow` + `.scan()`)**
   - Streams real-time Server-Sent Events (SSE) from `gemini-3.8-flash` over OkHttp without third-party AI SDK wrappers.
   - Separates **upstream delta accumulation** (via `.scan()` on `Dispatchers.Default`) from **UI state emission** (`StateFlow`), preventing dropped tokens under main-thread contention while avoiding redundant recompositions.
   - Guarantees deterministic socket teardown via `awaitClose { eventSource.cancel() }` and `withContext(NonCancellable)` when the user taps **Stop Generation** mid-stream.

2. **On-Device MCP Tool Registry (`AgentToolRegistry`)**
   - Exposes local SQLite capabilities (`get_expenses`, `insert_expense`, `delete_last_expense`) to the LLM via declarative JSON Schemas.
   - Distinguishes between **Read-Only Tools** (auto-executed mid-stream with sub-20ms measured latency badges) and **Mutating Write Tools** (suspended for explicit user confirmation).

3. **Human-in-the-Loop (HITL) Coroutine Suspension (`CompletableDeferred`)**
   - When the agent proposes a database mutation (`INSERT_EXPENSE` or `DELETE_LAST_ROW`), the streaming pipeline suspends inline on a `CompletableDeferred<Boolean>` and emits `MessageStreamStatus.AwaitingApproval`.
   - Jetpack Compose renders an interactive **Accept & Save / Reject** confirmation card inside the active message bubble. Once resolved by the user, the coroutine resumes and streams the final confirmation.

4. **Deterministic Guardrails & Context Budgeting (`ToolCallAuditor` & `ContextWindowManager`)**
   - Audits all LLM-generated tool arguments prior to execution—blocking hallucinated tool names, negative/NaN amounts, and out-of-bounds payloads.
   - Tracks real-time token consumption in the top bar (`1.4k / 8k tkns`) with sliding-window turn pruning.

5. **Dual-Engine Runtime (`Demo Sim` vs. `Gemini Live`)**
   - **Demo Sim Mode:** Zero-config offline simulator that executes real Room SQLite queries and triggers HITL approval cards out of the box—no API key required to evaluate the repo.
   - **Gemini Live Mode:** Connects to the live Google Gemini SSE endpoint (`gemini-3.8-flash`).

---

## 🏗️ End-to-End Streaming & Tool Execution Flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as Jetpack Compose (ChatScreen)
    participant VM as ChatViewModel (MVI)
    participant Audit as ToolCallAuditor
    participant Tool as AgentToolRegistry / Room DB
    participant LLM as GeminiSseClient (OkHttp SSE)

    User->>UI: Taps Send ("Spent on coffee? Log $28 dinner")
    UI->>VM: ChatIntent.SendPrompt
    VM->>LLM: streamTurn(prompt, toolSchemas)
    LLM-->>VM: LlmStreamEvent.FunctionCallRequested("get_expenses")
    VM->>Audit: audit("get_expenses", args)
    Audit-->>VM: AuditResult.Valid
    VM->>Tool: execute(category="Coffee") [Read-Only]
    Tool-->>VM: ToolExecutionResult ($42.50, 14ms)
    VM-->>UI: Emit ReadToolBadge + Stream Markdown Tokens
    LLM-->>VM: LlmStreamEvent.FunctionCallRequested("insert_expense")
    VM->>Audit: audit("insert_expense", amount=28.00)
    Audit-->>VM: AuditResult.Valid (requiresUserApproval = true)
    VM-->>UI: Emit PendingWriteCardState & Suspend on CompletableDeferred
    User->>UI: Taps "Accept & Save"
    UI->>VM: ChatIntent.ResolveWriteApproval(approved = true)
    VM->>Tool: execute(insert_expense) -> Room SQLite INSERT
    Tool-->>VM: Inserted Row #ID-12
    VM-->>UI: Resume Stream & Render Completion Summary
```

---

## 📂 Project Structure

```text
com.pocketagent
├── data
│   ├── llm/GeminiSseClient.kt         # Raw OkHttp SSE client with awaitClose socket cancellation
│   └── local/ExpenseDatabase.kt       # Room entities, DAO, and deterministic demo data seeder
├── domain
│   └── agent
│       ├── AgentToolRegistry.kt       # MCP tool contracts, JSON schemas & Room tool implementations
│       ├── ToolCallAuditor.kt         # Deterministic hallucination & bounds validation layer
│       └── ContextWindowManager.kt    # Heuristic token estimator & sliding-window turn pruner
└── presentation
    ├── ChatContract.kt                # @Immutable MVI state models, tool badges, and intents
    ├── ChatViewModel.kt               # Flow.scan() pipeline, CompletableDeferred HITL orchestrator
    ├── ChatScreen.kt                  # Stateless Material 3 dark UI & cached Markdown renderer
    └── DatabaseInspectorSheet.kt      # Live SQLite modal bottom sheet highlighting agent writes
```

---

## 🚀 Getting Started

1. **Clone & Run Immediately (Zero Config):**
   Open the project in Android Studio and press **Run**. If no API key is present, the app automatically boots in **Demo Sim** mode against the local Room database.
2. **Enable Gemini Live Mode (Optional):**
   Add a free [Google AI Studio](https://aistudio.google.com/) key to your root `local.properties`:
   ```properties
   GEMINI_API_KEY=AIzaSyYourKeyHere
   ```
3. **Run the Unit Test Suite:**
   ```bash
   ./gradlew testDebugUnitTest
   ```
   Verifies `CompletableDeferred` approval suspension, mid-stream `StopGeneration` cancellation cleanup, and `ToolCallAuditor` guardrails using `kotlinx-coroutines-test` and `Turbine`.
