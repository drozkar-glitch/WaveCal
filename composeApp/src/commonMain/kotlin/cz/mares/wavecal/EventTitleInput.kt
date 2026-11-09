package cz.mares.wavecal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun EventTitleInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isDark: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
)
