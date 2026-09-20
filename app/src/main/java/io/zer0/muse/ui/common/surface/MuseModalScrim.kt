package io.zer0.muse.ui.common.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Shared visual scrim for modal surfaces; platform window dim remains disabled. */
@Composable
internal fun museModalScrimColor(): Color =
    MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)
