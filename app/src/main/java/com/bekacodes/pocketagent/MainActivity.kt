package com.bekacodes.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bekacodes.pocketagent.data.PocketDatabase
import com.bekacodes.pocketagent.domain.AgentToolRegistry
import com.bekacodes.pocketagent.ui.presentation.chat.ChatScreen
import com.bekacodes.pocketagent.ui.presentation.chat.ChatViewModel
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme

/**
 * Single Activity entry point for PocketAgent. Initializes local Room database and delegates
 * MVI state flows to Jetpack Compose UI components.
 */
class MainActivity : ComponentActivity() {

    private val database by lazy { PocketDatabase.getInstance(applicationContext) }
    private val toolRegistry by lazy { AgentToolRegistry(database.expenseDao()) }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.provideFactory(database.expenseDao(), toolRegistry)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketAgentTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    uiState = uiState,
                    onIntent = viewModel::onIntent
                )
            }
        }
    }
}
