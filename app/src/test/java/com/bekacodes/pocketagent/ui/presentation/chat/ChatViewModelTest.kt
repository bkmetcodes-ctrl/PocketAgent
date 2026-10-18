package com.bekacodes.pocketagent.ui.presentation.chat

import com.bekacodes.pocketagent.data.ExpenseDao
import com.bekacodes.pocketagent.data.ExpenseEntity
import com.bekacodes.pocketagent.data.GeminiSseClient
import com.bekacodes.pocketagent.domain.AgentToolRegistry
import com.bekacodes.pocketagent.ui.presentation.EngineMode
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class FakeExpenseDao : ExpenseDao {
    private val expensesList = mutableListOf<ExpenseEntity>()
    private val expensesFlow = MutableStateFlow<List<ExpenseEntity>>(emptyList())

    override fun observeAllExpenses(): Flow<List<ExpenseEntity>> = expensesFlow

    override suspend fun getExpensesByCategory(category: String): List<ExpenseEntity> {
        return expensesList.filter { it.category.equals(category, ignoreCase = true) }
    }

    override suspend fun getAllExpensesSnapshot(): List<ExpenseEntity> {
        return expensesList.toList()
    }

    override suspend fun insertExpense(expense: ExpenseEntity): Long {
        val newId = (expensesList.maxOfOrNull { it.id } ?: 0L) + 1L
        val entity = expense.copy(id = newId)
        expensesList.add(entity)
        expensesFlow.value = expensesList.toList()
        return newId
    }

    override suspend fun deleteMostRecentExpense(): Int {
        return if (expensesList.isNotEmpty()) {
            expensesList.removeAt(expensesList.lastIndex)
            expensesFlow.value = expensesList.toList()
            1
        } else {
            0
        }
    }

    override suspend fun clearAll() {
        expensesList.clear()
        expensesFlow.value = emptyList()
    }

    override suspend fun count(): Int {
        return expensesList.size
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeExpenseDao: FakeExpenseDao
    private lateinit var toolRegistry: AgentToolRegistry
    private lateinit var geminiSseClient: GeminiSseClient
    private lateinit var viewModel: ChatViewModel

    private suspend fun <T> StateFlow<T>.waitUntil(
        timeoutMs: Long = 5000,
        condition: (T) -> Boolean,
    ): T {
        val start = System.currentTimeMillis()
        while (!condition(value)) {
            if ((System.currentTimeMillis() - start) > timeoutMs) {
                throw AssertionError("Timed out waiting for condition after ${timeoutMs}ms. Current state: $value")
            }
            withContext(Dispatchers.Default) {
                delay(20.milliseconds)
            }
        }
        return value
    }

    @Before
    fun setUp() {
        fakeExpenseDao = FakeExpenseDao()
        toolRegistry = AgentToolRegistry(fakeExpenseDao)
        geminiSseClient = GeminiSseClient("")
        viewModel = ChatViewModel(fakeExpenseDao, toolRegistry, geminiSseClient)
    }

    @Test
    fun initialization_seedsDemoDataAndObservesExpenses() = runTest {
        viewModel.uiState.waitUntil { it.expenses.isNotEmpty() }

        val state = viewModel.uiState.value
        assertTrue("Expenses should be seeded on init", state.expenses.isNotEmpty())
        assertEquals(11, state.expenses.size)

        assertTrue("Messages list should contain user prompt and agent response", state.messages.size >= 2)
        assertTrue(state.messages[0] is ChatMessageUiModel.UserMessage)
        assertTrue(state.messages[1] is ChatMessageUiModel.AgentMessage)
    }

    @Test
    fun onIntent_UpdateInput_updatesInputText() {
        val testInput = "How much did I spend on groceries?"
        viewModel.onIntent(ChatIntent.UpdateInput(testInput))

        assertEquals(testInput, viewModel.uiState.value.inputText)
    }

    @Test
    fun onIntent_SwitchEngineMode_updatesEngineMode() {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.GEMINI_LIVE))
        assertEquals(EngineMode.GEMINI_LIVE, viewModel.uiState.value.engineMode)

        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))
        assertEquals(EngineMode.DEMO_SIM, viewModel.uiState.value.engineMode)
    }

    @Test
    fun onIntent_ToggleInspector_updatesInspectorVisibility() {
        viewModel.onIntent(ChatIntent.ToggleInspector(visible = true))
        assertTrue(viewModel.uiState.value.isInspectorVisible)

        viewModel.onIntent(ChatIntent.ToggleInspector(visible = false))
        assertFalse(viewModel.uiState.value.isInspectorVisible)
    }

    @Test
    fun onIntent_ResetDemoDatabase_clearsAndReseedsDatabase() = runTest {
        viewModel.uiState.waitUntil { it.expenses.size == 11 }

        fakeExpenseDao.clearAll()
        viewModel.uiState.waitUntil { it.expenses.isEmpty() }
        assertEquals(0, viewModel.uiState.value.expenses.size)

        viewModel.onIntent(ChatIntent.ResetDemoDatabase)
        viewModel.uiState.waitUntil { it.expenses.size == 11 }

        assertEquals(11, viewModel.uiState.value.expenses.size)
    }

    @Test
    fun onIntent_SendPrompt_summaryPrompt_executesGetExpensesToolAndCompletes() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))

        val prompt = "Give me a summary of my expenses"
        viewModel.onIntent(ChatIntent.SendPrompt(prompt))

        assertEquals("", viewModel.uiState.value.inputText)

        viewModel.uiState.waitUntil { state ->
            (!state.isGenerating) && (state.messages.lastOrNull()?.let {
                (it is ChatMessageUiModel.AgentMessage) && (it.status == MessageStreamStatus.Completed)
            } == true)
        }

        val state = viewModel.uiState.value
        assertFalse("isGenerating should be false after completion", state.isGenerating)

        val lastAgentMsg = state.messages.last() as ChatMessageUiModel.AgentMessage
        assertEquals(MessageStreamStatus.Completed, lastAgentMsg.status)
        assertTrue("Executed read tools should contain get_expenses", lastAgentMsg.executedReadTools.any { it.callSignature.contains("get_expenses") })
        assertTrue("Agent response should summarize total spending", lastAgentMsg.markdownText.contains("recorded transactions"))
    }

    @Test
    fun onIntent_SendPrompt_undoPrompt_requestsApproval_and_whenApproved_deletesLastExpense() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))

        viewModel.uiState.waitUntil { it.expenses.size == 11 }
        val initialCount = fakeExpenseDao.count()

        viewModel.onIntent(ChatIntent.SendPrompt("Undo last expense"))

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.pendingWriteApproval != null
        }

        val state = viewModel.uiState.value
        val agentMsg = state.messages.last() as ChatMessageUiModel.AgentMessage

        val approvalCard = agentMsg.pendingWriteApproval
        assertNotNull("Write approval card should be requested", approvalCard)
        assertEquals("DELETE_LAST_ROW", approvalCard?.actionName)
        assertEquals(MessageStreamStatus.AwaitingApproval, agentMsg.status)

        viewModel.onIntent(
            ChatIntent.ResolveWriteApproval(
                messageId = agentMsg.id,
                callId = approvalCard!!.callId,
                approved = true,
            ),
        )

        viewModel.uiState.waitUntil { s ->
            val last = s.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.status == MessageStreamStatus.Completed
        }

        val updatedState = viewModel.uiState.value
        val completedAgentMsg = updatedState.messages.last() as ChatMessageUiModel.AgentMessage

        assertEquals(MessageStreamStatus.Completed, completedAgentMsg.status)
        assertTrue("Markdown text should confirm deletion", completedAgentMsg.markdownText.contains("Done!"))
        assertEquals(initialCount - 1, fakeExpenseDao.count())
    }

    @Test
    fun onIntent_SendPrompt_undoPrompt_requestsApproval_and_whenRejected_keepsLastExpense() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))

        viewModel.uiState.waitUntil { it.expenses.size == 11 }
        val initialCount = fakeExpenseDao.count()

        viewModel.onIntent(ChatIntent.SendPrompt("Delete last expense"))

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.pendingWriteApproval != null
        }

        val agentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage
        val approvalCard = agentMsg.pendingWriteApproval
        assertNotNull(approvalCard)

        viewModel.onIntent(
            ChatIntent.ResolveWriteApproval(
                messageId = agentMsg.id,
                callId = approvalCard!!.callId,
                approved = false,
            ),
        )

        viewModel.uiState.waitUntil { s ->
            val last = s.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.status == MessageStreamStatus.Completed
        }

        val completedAgentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage

        assertEquals(MessageStreamStatus.Completed, completedAgentMsg.status)
        assertTrue("Markdown text should confirm cancellation", completedAgentMsg.markdownText.contains("Cancelled"))
        assertEquals(initialCount, fakeExpenseDao.count())
    }

    @Test
    fun onIntent_SendPrompt_generalPrompt_requestsApproval_and_whenApproved_insertsExpense() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))

        viewModel.uiState.waitUntil { it.expenses.size == 11 }
        val initialCount = fakeExpenseDao.count()

        viewModel.onIntent(ChatIntent.SendPrompt("I bought a $28 dinner"))

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.pendingWriteApproval != null
        }

        val agentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage
        val approvalCard = agentMsg.pendingWriteApproval
        assertNotNull("Approval card for insert should be present", approvalCard)
        assertEquals("INSERT_EXPENSE", approvalCard?.actionName)

        viewModel.onIntent(
            ChatIntent.ResolveWriteApproval(
                messageId = agentMsg.id,
                callId = approvalCard!!.callId,
                approved = true,
            ),
        )

        viewModel.uiState.waitUntil { s ->
            val last = s.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.status == MessageStreamStatus.Completed
        }

        val completedAgentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage

        assertEquals(MessageStreamStatus.Completed, completedAgentMsg.status)
        assertTrue("Markdown should indicate success", completedAgentMsg.markdownText.contains("Saved!"))
        assertEquals(initialCount + 1, fakeExpenseDao.count())
    }

    @Test
    fun onIntent_StopGeneration_cancelsActiveGenerationJob() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.DEMO_SIM))

        viewModel.onIntent(ChatIntent.SendPrompt("Undo last expense"))

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.pendingWriteApproval != null
        }

        val agentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage
        assertEquals(MessageStreamStatus.AwaitingApproval, agentMsg.status)

        viewModel.onIntent(ChatIntent.StopGeneration)

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.status == MessageStreamStatus.InterruptedByUser
        }

        val stoppedAgentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage
        assertEquals(MessageStreamStatus.InterruptedByUser, stoppedAgentMsg.status)
        assertFalse(viewModel.uiState.value.isGenerating)
    }

    @Test
    fun geminiLiveMode_streamError_whenApiKeyIsBlank() = runTest {
        viewModel.onIntent(ChatIntent.SwitchEngineMode(EngineMode.GEMINI_LIVE))

        viewModel.onIntent(ChatIntent.SendPrompt("Hello Gemini"))

        viewModel.uiState.waitUntil { state ->
            val last = state.messages.lastOrNull() as? ChatMessageUiModel.AgentMessage
            last?.status == MessageStreamStatus.Completed
        }

        val agentMsg = viewModel.uiState.value.messages.last() as ChatMessageUiModel.AgentMessage
        assertTrue(
            "Agent message should report stream error when API key is blank",
            agentMsg.markdownText.contains("Stream Error") || agentMsg.markdownText.contains("Missing GEMINI_API_KEY"),
        )
        assertEquals(MessageStreamStatus.Completed, agentMsg.status)
    }

    @Test
    fun provideFactory_createsViewModelInstance() {
        val factory = ChatViewModel.provideFactory(fakeExpenseDao, toolRegistry)
        val vm = factory.create(ChatViewModel::class.java)
        assertNotNull(vm)
    }
}
