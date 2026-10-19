package com.bekacodes.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bekacodes.pocketagent.ui.presentation.chat.ChatScreen
import com.bekacodes.pocketagent.ui.presentation.chat.ChatViewModel
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity entry point for PocketAgent. Annotated with [@AndroidEntryPoint]
 * to enable Hilt view model and field injection.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketAgentTheme {
                val viewModel: ChatViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    uiState = uiState,
                    onIntent = viewModel::onIntent
                )
            }
        }
    }
}
