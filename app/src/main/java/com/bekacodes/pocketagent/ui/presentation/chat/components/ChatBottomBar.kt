package com.bekacodes.pocketagent.ui.presentation.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme
import com.bekacodes.pocketagent.ui.theme.PocketColors

/**
 * Bottom Bar component containing quick action suggestion pills, text entry field, and animated send/stop action button.
 */
@Composable
fun ChatBottomBar(
    inputText: String,
    isGenerating: Boolean,
    suggestions: List<String>,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onStopClicked: () -> Unit,
    onSuggestionClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !isGenerating) { onSuggestionClicked(suggestion) }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Suggestion: $suggestion"
                        }
                ) {
                    Text(
                        text = suggestion,
                        color = PocketColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (isGenerating) "Agent is active..." else "Ask or log an expense...",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    enabled = !isGenerating,
                    textStyle = TextStyle(color = PocketColors.TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Prompt input field"
                        }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            AnimatedContent(targetState = isGenerating, label = "send_stop_btn") { generating ->
                if (generating) {
                    IconButton(
                        onClick = onStopClicked,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Stop generation"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop generation icon",
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSendClicked,
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = "Send prompt"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Send prompt icon",
                            tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun ChatBottomBarIdlePreview() {
    PocketAgentTheme {
        ChatBottomBar(
            inputText = "",
            isGenerating = false,
            suggestions = listOf("Monthly summary", "Undo last entry", "Clear all data"),
            onInputChanged = {},
            onSendClicked = {},
            onStopClicked = {},
            onSuggestionClicked = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun ChatBottomBarWithInputPreview() {
    PocketAgentTheme {
        ChatBottomBar(
            inputText = "Log $14 for coffee",
            isGenerating = false,
            suggestions = listOf("Monthly summary", "Undo last entry", "Clear all data"),
            onInputChanged = {},
            onSendClicked = {},
            onStopClicked = {},
            onSuggestionClicked = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun ChatBottomBarGeneratingPreview() {
    PocketAgentTheme {
        ChatBottomBar(
            inputText = "",
            isGenerating = true,
            suggestions = listOf("Monthly summary", "Undo last entry", "Clear all data"),
            onInputChanged = {},
            onSendClicked = {},
            onStopClicked = {},
            onSuggestionClicked = {}
        )
    }
}
