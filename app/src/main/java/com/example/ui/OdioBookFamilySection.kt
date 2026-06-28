package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * §777 — "The OdioBook Family" cross-promotion section.
 *
 * A single, self-contained Composable shared (copy-identical) across every
 * OdioBook-family Android app — Veda Drop, Early Rover, Dig Deep & Xello Mind.
 * It turns each app into a discovery surface for the whole family, the way big
 * product houses cross-promote their suite: every app quietly tells the user
 * about the others, so a download of one can lift them all, and every tap funnels
 * traffic back to odiobook.com.
 *
 * Deliberately dependency-free: pure Compose + Material3 theme colours (so it
 * inherits each app's look automatically) + the bundled `odiobook_logo` drawable
 * + LocalUriHandler for the clickable links. No network, no Coil, no new Gradle
 * deps — drop the file in, add the drawable, call the function.
 *
 * @param currentAppTitle the host app's family title (e.g. "Early Rover") so its
 *        own card renders as "You're here" instead of a tappable link.
 */
private data class FamilyApp(
    val title: String,
    val tagline: String,
    val accent: Color,
    val url: String,
)

// The canonical family. Order = the public odiobook.com/apps directory.
// Tapping any sibling opens the OdioBook apps hub (always-current store links
// live there); OdioBook itself opens the main site.
private val FAMILY_APPS = listOf(
    FamilyApp(
        "OdioBook",
        "AI voice cloning, text-to-speech & studio",
        Color(0xFF6D5EF6),
        "https://odiobook.com",
    ),
    FamilyApp(
        "Veda Drop",
        "Women-only beauty & wellness booking",
        Color(0xFF00AAAD),
        "https://odiobook.com/apps",
    ),
    FamilyApp(
        "Early Rover",
        "Smart alarm, weather & travel wake-up",
        Color(0xFFF5A623),
        "https://odiobook.com/apps",
    ),
    FamilyApp(
        "Dig Deep",
        "Secure data shredder & recovery",
        Color(0xFF10B981),
        "https://odiobook.com/apps",
    ),
    FamilyApp(
        "Xello Mind",
        "Active-memory trainer with speech feedback",
        Color(0xFF13B4A2),
        "https://odiobook.com/apps",
    ),
)

private const val ODIOBOOK_HOME = "https://odiobook.com"

@Composable
fun OdioBookFamilySection(
    currentAppTitle: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.62f)
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "OUR APPS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = primary,
        )
        Spacer(Modifier.height(10.dp))

        // OdioBook header — logo + family mission, tap to open the main site.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { uriHandler.openUri(ODIOBOOK_HOME) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.odiobook_logo),
                contentDescription = "OdioBook",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "The OdioBook Family",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "One studio, a family of apps that make everyday life easier.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = muted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        FAMILY_APPS.forEach { app ->
            FamilyAppRow(
                app = app,
                isCurrent = app.title.equals(currentAppTitle, ignoreCase = true),
                onOpen = { uriHandler.openUri(app.url) },
                onSurface = onSurface,
                muted = muted,
                primary = primary,
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { uriHandler.openUri(ODIOBOOK_HOME) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Explore everything on OdioBook.com")
        }
    }
}

@Composable
private fun FamilyAppRow(
    app: FamilyApp,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onSurface: Color,
    muted: Color,
    primary: Color,
) {
    val shape = RoundedCornerShape(16.dp)
    var rowModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .border(1.dp, onSurface.copy(alpha = 0.12f), shape)
    if (!isCurrent) {
        rowModifier = rowModifier.clickable { onOpen() }
    }

    Row(
        modifier = rowModifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monogram tile in the app's accent colour.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(app.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.title.take(1),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "You're here",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.tagline,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = muted,
            )
        }
        if (!isCurrent) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 22.sp,
                color = muted,
            )
        }
    }
}
