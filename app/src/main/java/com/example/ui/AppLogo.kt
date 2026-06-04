package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.example.R

/**
 * A dynamic App Logo component that acts as the single source of truth for the app's branding.
 * Use this everywhere the app logo needs to be displayed.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Image(
        painter = painterResource(id = R.drawable.ic_app_logo),
        contentDescription = "Early Rover Logo",
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
        modifier = modifier
    )
}
