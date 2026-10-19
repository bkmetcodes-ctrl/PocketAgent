package com.bekacodes.pocketagent.ui.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bekacodes.pocketagent.ui.theme.PocketAgentTheme
import com.bekacodes.pocketagent.ui.theme.PocketColors
import java.util.Locale

/**
 * Top App Bar component featuring title, Engine Mode toggle switch, and live token usage badge.
 */
@Composable
fun PocketAgentTopBar(
    usedTokens: Int,
    maxTokens: Int,
    toggleInspector: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics {
                    contentDescription = "PocketAgent application title"
                }
            ) {
                Text(
                    text = "PocketAgent",
                    color = PocketColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "✦",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp
                )
            }

            val formattedUsed = String.format(Locale.US, "%.1f", usedTokens / 1000f)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        onClickLabel = "Open Room SQLite Inspector",
                        onClick = { toggleInspector(true) }
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            "Token usage: $formattedUsed K of ${maxTokens / 1000}K tokens. Tap to inspect Room database."
                    }
            ) {
                Text(
                    text = "${formattedUsed}k / ${maxTokens / 1000}k\ntkns",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun PocketAgentTopBarDemoSimPreview() {
    PocketAgentTheme {
        PocketAgentTopBar(
            usedTokens = 1400,
            maxTokens = 8000,
            toggleInspector = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun PocketAgentTopBarGeminiLivePreview() {
    PocketAgentTheme {
        PocketAgentTopBar(
            usedTokens = 3200,
            maxTokens = 8000,
            toggleInspector = {}
        )
    }
}
