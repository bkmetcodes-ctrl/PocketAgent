package com.bekacodes.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bekacodes.pocketagent.ui.presentation.chat.ChatScreen
import com.bekacodes.pocketagent.ui.presentation.chat.ChatViewModel
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

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