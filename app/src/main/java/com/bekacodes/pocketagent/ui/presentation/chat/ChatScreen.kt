package com.bekacodes.pocketagent.ui.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bekacodes.pocketagent.ui.DatabaseInspectorSheet
import com.bekacodes.pocketagent.ui.presentation.chat.components.AgentMessageItem
import com.bekacodes.pocketagent.ui.presentation.chat.components.ChatBottomBar
import com.bekacodes.pocketagent.ui.presentation.chat.components.EngineModePill
import com.bekacodes.pocketagent.ui.presentation.chat.components.PocketAgentTopBar
import com.bekacodes.pocketagent.ui.presentation.chat.components.UserMessageBubble
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatIntent
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatUiState
import com.bekacodes.pocketagent.ui.presentation.chat.model.EngineMode
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import com.bekacodes.pocketagent.ui.presentation.chat.model.PendingWriteCardState
import com.bekacodes.pocketagent.ui.presentation.chat.model.ReadToolBadge
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme

/**
 * Main Chat Screen composable rendering conversation turns, top navigation, error banners, and bottom prompt bar.
 */
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val latestMessageContent = remember(uiState.messages) {
        uiState.messages.lastOrNull()?.let { msg ->
            when (msg) {
                is ChatMessageUiModel.UserMessage -> msg.text
                is ChatMessageUiModel.AgentMessage -> "${msg.markdownText}_${msg.pendingWriteApproval?.resolution}_${msg.status}"
            }
        }
    }

    LaunchedEffect(uiState.messages.size, latestMessageContent) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex + 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                PocketAgentTopBar(
                    usedTokens = uiState.usedTokens,
                    maxTokens = uiState.maxTokens,
                    toggleInspector = { onIntent(ChatIntent.ToggleInspector(it)) }
                )

                uiState.errorMessage?.let { errorText ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Error message: $errorText" }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorText,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = { onIntent(ChatIntent.DismissError) },
                                modifier = Modifier.semantics {
                                    role = Role.Button
                                    contentDescription = "Dismiss error"
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close error banner",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            ChatBottomBar(
                inputText = uiState.inputText,
                isGenerating = uiState.isGenerating,
                suggestions = uiState.quickSuggestions,
                onInputChanged = { onIntent(ChatIntent.UpdateInput(it)) },
                onSendClicked = {
                    if (uiState.inputText.isNotBlank()) {
                        onIntent(ChatIntent.SendPrompt(uiState.inputText))
                    }
                },
                onStopClicked = { onIntent(ChatIntent.StopGeneration) },
                onSuggestionClicked = { onIntent(ChatIntent.SendPrompt(it)) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Spacer(Modifier.height(36.dp)) }
            items(
                items = uiState.messages,
                key = { it.id },
                contentType = { message ->
                    when (message) {
                        is ChatMessageUiModel.UserMessage -> "user_msg"
                        is ChatMessageUiModel.AgentMessage -> "agent_msg"
                    }
                }
            ) { message ->
                when (message) {
                    is ChatMessageUiModel.UserMessage -> UserMessageBubble(message = message)
                    is ChatMessageUiModel.AgentMessage -> AgentMessageItem(
                        message = message,
                        onApproveWrite = {
                            onIntent(
                                ChatIntent.ResolveWriteApproval(
                                    message.id,
                                    approved = true
                                )
                            )
                        },
                        onRejectWrite = {
                            onIntent(
                                ChatIntent.ResolveWriteApproval(
                                    message.id,
                                    approved = false
                                )
                            )
                        }
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = innerPadding.calculateTopPadding() + 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EngineModePill(
                    label = "Demo Sim",
                    selected = uiState.engineMode == EngineMode.DEMO_SIM,
                    onClick = { onIntent(ChatIntent.SwitchEngineMode((EngineMode.DEMO_SIM))) }
                )
                EngineModePill(
                    label = "Gemini Live",
                    selected = uiState.engineMode == EngineMode.GEMINI_LIVE,
                    onClick = { onIntent(ChatIntent.SwitchEngineMode((EngineMode.GEMINI_LIVE))) }
                )
            }
        }
    }

    if (uiState.isInspectorVisible) {
        DatabaseInspectorSheet(
            expenses = uiState.expenses,
            onClearDatabase = { onIntent(ChatIntent.ClearDatabase) },
            onDismiss = { onIntent(ChatIntent.ToggleInspector(false)) }
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 917)
@Composable
private fun ChatScreenFigmaPreview() {
    val sampleState = ChatUiState(
        engineMode = EngineMode.GEMINI_LIVE,
        usedTokens = 1400,
        maxTokens = 8000,
        messages = listOf(
            ChatMessageUiModel.UserMessage(
                id = "msg_1",
                timestamp = "9:41 AM",
                text = "How much did I spend on coffee this month, and log a $28 dinner from last night."
            ),
            ChatMessageUiModel.AgentMessage(
                id = "msg_2",
                timestamp = "9:41 AM",
                executedReadTools = listOf(
                    ReadToolBadge(
                        callSignature = "get_expenses(category='Coffee')",
                        durationMs = 14
                    )
                ),
                markdownText = "You've spent **$42.50** across 9 coffee visits in October. Before I update your ledger, please confirm the new dinner entry:",
                pendingWriteApproval = PendingWriteCardState(
                    callId = "call_99",
                    actionName = "INSERT_EXPENSE",
                    fields = listOf(
                        "Amount" to "$28.00",
                        "Category" to "Dining Out",
                        "Date" to "Yesterday"
                    )
                ),
                status = MessageStreamStatus.AwaitingApproval
            )
        )
    )

    PocketAgentTheme {
        ChatScreen(uiState = sampleState, onIntent = {})
    }
}
