package spam.blocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import spam.blocker.G
import spam.blocker.ui.isLight


@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = (if (G.palette.background.isLight()) lightColorScheme() else darkColorScheme()).copy(
            primary = G.palette.infoBlue,
            onPrimary = G.palette.background,
            secondary = G.palette.teal200,
            background = G.palette.background,
            surface = G.palette.background,
            surfaceContainer = G.palette.dialogBg,
            error = G.palette.error,
        ),
        content = content,
    )
}
