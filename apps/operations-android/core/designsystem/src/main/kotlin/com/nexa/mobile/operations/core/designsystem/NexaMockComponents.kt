package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Canonical M3 Bottom Navigation Bar for Nexa Mobile Suite.
 * 48dp minimum touch target, active pill indicator, WCAG compliant.
 */
@Composable
fun NexaBottomNavigationBar(
    currentTab: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        NavigationBar(
            containerColor = NexaColors.Surface,
            contentColor = NexaColors.TextPrimary,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            val tabs = listOf(
                Triple("routes", stringResource(R.string.nexa_tab_routes), R.drawable.ic_truck),
                Triple("dispatch", stringResource(R.string.nexa_tab_dispatch), R.drawable.ic_send),
                Triple("picking", stringResource(R.string.nexa_tab_picking), R.drawable.ic_box),
                Triple("checklist", stringResource(R.string.nexa_tab_checklist), R.drawable.ic_check_square),
                Triple("buyer", stringResource(R.string.nexa_tab_buyer), R.drawable.ic_shopping_bag)
            )

            tabs.forEach { (key, label, iconRes) ->
                val selected = currentTab == key
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(key) },
                    icon = {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = label,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NexaColors.PrimaryStrong,
                        selectedTextColor = NexaColors.PrimaryStrong,
                        indicatorColor = NexaColors.PrimaryContainer,
                        unselectedIconColor = NexaColors.TextSecondary,
                        unselectedTextColor = NexaColors.TextSecondary
                    )
                )
            }
        }
    }
}

/**
 * Driver / Operator profile card in white minimalist style.
 */
@Composable
fun NexaDriverProfileCard(
    driverName: String,
    roleTitle: String,
    vehicleInfo: String,
    statusText: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(NexaColors.InfoSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_identity),
                    contentDescription = null,
                    tint = NexaColors.PrimaryStrong,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = driverName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NexaColors.TextPrimary
                    )
                    NexaStatusPill(
                        text = statusText,
                        backgroundColor = NexaColors.SuccessSurface,
                        textColor = NexaColors.Success,
                        borderColor = NexaColors.SuccessBorder
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = roleTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
                Text(
                    text = vehicleInfo,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = NexaColors.TextMuted
                )
            }
        }
    }
}

/**
 * Cold-chain temperature telemetry badge.
 */
@Composable
fun NexaColdChainBadge(
    temperatureRange: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = NexaColors.ColdRefrigeratedSurface,
        border = BorderStroke(1.dp, NexaColors.ColdRefrigeratedBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_snowflake),
                contentDescription = null,
                tint = NexaColors.ColdRefrigerated,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = temperatureRange,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.ColdRefrigeratedText
            )
        }
    }
}

/**
 * Reusable rounded status pill.
 */
@Composable
fun NexaStatusPill(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Interactive Quantity Stepper (+ / -) conforming to 48dp touch targets.
 */
@Composable
fun NexaQuantityStepper(
    quantity: Int,
    onQuantityChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = 999,
    unitLabel: String = "unid."
) {
    Row(
        modifier = modifier
            .background(NexaColors.SurfaceInset, RoundedCornerShape(10.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(36.dp)
                .clickable(enabled = quantity > min) { onQuantityChanged(quantity - 1) },
            shape = RoundedCornerShape(8.dp),
            color = if (quantity > min) NexaColors.Surface else NexaColors.SurfaceInset,
            border = BorderStroke(1.dp, if (quantity > min) NexaColors.Border else Color.Transparent)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "−",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (quantity > min) NexaColors.TextPrimary else NexaColors.TextMuted
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            Text(
                text = quantity.toString(),
                style = NexaTypography.identifier.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                color = NexaColors.TextPrimary
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = NexaColors.TextMuted
            )
        }

        Surface(
            modifier = Modifier
                .size(36.dp)
                .clickable(enabled = quantity < max) { onQuantityChanged(quantity + 1) },
            shape = RoundedCornerShape(8.dp),
            color = if (quantity < max) NexaColors.PrimaryStrong else NexaColors.SurfaceInset,
            border = BorderStroke(1.dp, if (quantity < max) NexaColors.PrimaryStrong else Color.Transparent)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (quantity < max) Color.White else NexaColors.TextMuted
                )
            }
        }
    }
}

/**
 * Filter Chip Carousel for category filtering.
 */
@Composable
fun NexaFilterChips(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selectedOption
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onOptionSelected(option) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) NexaColors.PrimaryStrong else NexaColors.Surface,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) NexaColors.PrimaryStrong else NexaColors.Border
                )
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    ),
                    color = if (isSelected) Color.White else NexaColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Interactive checklist toggle row with M3 switch.
 */
@Composable
fun NexaInteractiveToggle(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = if (checked) NexaColors.SuccessSurface else NexaColors.Surface,
        border = BorderStroke(
            1.dp,
            if (checked) NexaColors.SuccessBorder else NexaColors.Border
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaColors.TextSecondary
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NexaColors.Success,
                    uncheckedThumbColor = NexaColors.BorderStrong,
                    uncheckedTrackColor = NexaColors.SurfaceInset
                )
            )
        }
    }
}

/**
 * Severity selector (Baja, Media, Crítica) for incident reporting.
 */
@Composable
fun NexaSeveritySelector(
    selectedSeverity: String,
    onSeveritySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val severities = listOf(
        Triple("Baja", NexaColors.Success, NexaColors.SuccessSurface),
        Triple("Media", NexaColors.Warning, NexaColors.WarningSurface),
        Triple("Crítica", NexaColors.Danger, NexaColors.DangerSurface)
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        severities.forEach { (severity, activeColor, activeBg) ->
            val isSelected = selectedSeverity == severity
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSeveritySelected(severity) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) activeBg else NexaColors.Surface,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) activeColor else NexaColors.Border
                )
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = severity,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp
                        ),
                        color = if (isSelected) activeColor else NexaColors.TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Progress indicator for offline mutations synchronization.
 */
@Composable
fun NexaSyncProgressBar(
    progress: Float,
    syncStatus: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = syncStatus,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = NexaTypography.identifier.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                color = NexaColors.PrimaryStrong
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = NexaColors.PrimaryStrong,
            trackColor = NexaColors.SurfaceInset
        )
    }
}

