package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val LocalNexaProfileAction = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun NexaProfileAvatar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.nexa_open_profile)
    Surface(
        onClick = onClick,
        modifier = modifier.size(NexaSizes.minimumTouchTarget).semantics {
            contentDescription =
                description
        },
        shape = CircleShape,
        color = NexaColors.InfoSurface,
        contentColor = NexaColors.PrimaryStrong
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_identity),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
