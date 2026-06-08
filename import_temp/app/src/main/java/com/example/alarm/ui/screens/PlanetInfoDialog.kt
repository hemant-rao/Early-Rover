package com.example.alarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.alarm.opengl.PlanetFacts
import com.example.ui.theme.SleekActiveText
import com.example.ui.theme.SleekBorder
import com.example.ui.theme.SleekCardBg
import com.example.ui.theme.SleekMutedText
import com.example.ui.theme.SleekPrimary

/**
 * Info dialog shown when the user taps a body in the 3D solar system.
 *
 * Rendered with a real platform [Dialog] so it appears above the GLSurfaceView,
 * which is drawn z-order-on-top of the rest of the Compose hierarchy.
 */
@Composable
fun PlanetInfoDialog(
    planetName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val emoji = remember(planetName) { PlanetFacts.emojiFor(context, planetName) }
    var fact by remember(planetName) {
        mutableStateOf(PlanetFacts.nextFact(context, planetName))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SleekCardBg,
            border = BorderStroke(1.dp, SleekBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title: emoji + planet name
                Text(
                    text = if (emoji.isNotBlank()) "$emoji  $planetName" else planetName,
                    color = SleekActiveText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                // Current fact
                Text(
                    text = fact,
                    color = SleekMutedText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 24.dp)
                )

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Close",
                            color = SleekMutedText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = { fact = PlanetFacts.nextFact(context, planetName) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SleekPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Next fact",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
