package com.fenixhub.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FenixDarkColors = darkColorScheme(
    primary = HubBlue,
    secondary = HubTextSoft,
    tertiary = HubBlue,
    background = HubNight,
    surface = HubPanel,
    surfaceVariant = HubPanelSoft,
)

@Composable
fun FenixHubTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) FenixDarkColors else FenixDarkColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
