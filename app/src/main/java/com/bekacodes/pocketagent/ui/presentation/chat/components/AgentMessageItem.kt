package com.bekacodes.pocketagent.ui.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bekacodes.pocketagent.ui.presentation.ApprovalResolution
import com.bekacodes.pocketagent.ui.presentation.chat.model.MessageStreamStatus
import com.bekacodes.pocketagent.ui.presentation.chat.model.ChatMessageUiModel
import com.bekacodes.pocketagent.ui.presentation.chat.model.PendingWriteCardState
import com.bekacodes.pocketagent.ui.theme.PocketColors

@Composable
fun AgentMessageItem(
    message: ChatMessageUiModel.AgentMessage,
    onApproveWrite: (String) -> Unit,
    onRejectWrite: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✦",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            message.executedReadTools.forEach { badge ->
                ReadToolChip(badge = badge)
            }

            if (message.markdownText.isNotEmpty()) {
                Text(
                    text = rememberParsedMarkdown(message.markdownText),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    lineHeight = 23.sp
                )
            }

            message.pendingWriteApproval?.let { cardState ->
                PendingWriteApprovalCard(
                    state = cardState,
                    onApprove = { onApproveWrite(cardState.callId) },
                    onReject = { onRejectWrite(cardState.callId) }
                )
            }

            if (message.status == MessageStreamStatus.InterruptedByUser) {
                Surface(
                    color = PocketColors.Amber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, PocketColors.Amber.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "⏹ Generation stopped by user",
                        color = PocketColors.Amber,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadToolChip(badge: ReadToolBadge) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, PocketColors.Emerald.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (badge.isRunning) {
                    "Running Local DB: ${badge.callSignature}..."
                } else {
                    "Queried Local DB: ${badge.callSignature} • ${badge.durationMs}ms"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PendingWriteApprovalCard(
    state: PendingWriteCardState,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PocketColors.Amber.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = PocketColors.Amber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = PocketColors.Amber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "Pending Database Write",
                        color = PocketColors.Amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Action",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Surface(
                    color = PocketColors.Indigo.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = state.actionName,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            state.fields.forEach { (label, value) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Text(
                        text = value,
                        color = PocketColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (state.resolution) {
                ApprovalResolution.PENDING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PocketColors.TextPrimary
                            )
                        ) {
                            Text(text = "Reject", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Button(
                            onClick = onApprove,
                            modifier = Modifier
                                .weight(1.2f)
                                .height(46.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            )
                        ) {
                            Text(
                                text = "Accept & Save",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                ApprovalResolution.APPROVED -> {
                    Text(
                        text = "✓ Saved to Local Room Database",
                        color = PocketColors.EmeraldSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                ApprovalResolution.REJECTED -> {
                    Text(
                        text = "✕ Write Rejected by User",
                        color = PocketColors.Coral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberParsedMarkdown(rawText: String) = remember(rawText) {
    buildAnnotatedString {
        val parts = rawText.split("**")
        parts.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = PocketColors.TextPrimary
                    )
                ) {
                    append(segment)
                }
            } else {
                append(segment)
            }
        }
    }
}
