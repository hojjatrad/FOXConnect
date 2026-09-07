package com.foxconnect.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxconnect.app.R
import com.foxconnect.core.model.ConnectableProfile
import com.foxconnect.core.model.ConnectionState
import com.foxconnect.core.model.ProtocolType
import com.foxconnect.core.model.TunnelSnapshot
import com.foxconnect.core.model.TunnelStats
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

@Composable
fun HomeScreen(
    snapshot: TunnelSnapshot,
    selectedProfile: ConnectableProfile?,
    onConnectionClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLogsClick: () -> Unit,
    onConfigsClick: () -> Unit,
) {
    val displayedConnection = HomePresentationPolicy.displayedConnection(
        state = snapshot.state,
        runtimeName = snapshot.profileName,
        runtimeProtocol = snapshot.protocol,
        selectedName = selectedProfile?.name,
        selectedProtocol = selectedProfile?.protocol,
    )
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(CanvasColor),
    ) {
        val orbSize = if (maxHeight < 650.dp) 176.dp else 184.dp
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ConnectionOrb(snapshot.state, orbSize, onConnectionClick)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = statusText(snapshot.state),
                    style = MaterialTheme.typography.titleLarge,
                    color = stateColor(snapshot.state),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                ConnectionSelector(
                    connection = displayedConnection,
                    enabled = snapshot.state is ConnectionState.Disconnected || snapshot.state is ConnectionState.Failed,
                    onClick = onProfileClick,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val message = (snapshot.state as? ConnectionState.Failed)?.userMessage
                        ?: if (selectedProfile == null) stringResource(R.string.choose_config_hint) else null
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (snapshot.state is ConnectionState.Failed) ErrorColor else MutedColor,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LiveFacts(snapshot.stats)
            }
            QuickNavigation(onSettingsClick, onLogsClick, onConfigsClick)
        }
    }
}

@Composable
private fun ConnectionOrb(state: ConnectionState, diameter: Dp, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) 90 else 180,
            easing = FastOutSlowInEasing,
        ),
        label = "connection-press",
    )
    val infinite = rememberInfiniteTransition(label = "connection-motion")
    val pulse by infinite.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idle-pulse",
    )
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        label = "loading-arc",
    )
    val connectedFill by animateFloatAsState(
        targetValue = if (state is ConnectionState.Connected) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "connected-fill",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state is ConnectionState.Failed) {
        if (state is ConnectionState.Failed) {
            listOf(-6f, 6f, -4f, 4f, 0f).forEach { target ->
                shake.animateTo(target, tween(60, easing = LinearEasing))
            }
        } else {
            shake.snapTo(0f)
        }
    }
    val description = stringResource(R.string.connection_control_description)
    val stateDescriptionText = statusText(state)
    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                translationX = shake.value
            }
            .semantics {
                contentDescription = description
                stateDescription = stateDescriptionText
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onClick()
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val ringSize = Size(size.width - inset * 2, size.height - inset * 2)
            val ringTopLeft = Offset(inset, inset)
            when (state) {
                is ConnectionState.Disconnected -> {
                    drawCircle(PrimaryIdleColor.copy(alpha = pulse), style = Stroke(stroke))
                    drawPowerGlyph(PrimaryIdleColor)
                }
                is ConnectionState.Connecting,
                is ConnectionState.Switching -> {
                    drawCircle(DividerColor, style = Stroke(stroke))
                    drawArc(
                        color = PrimaryIdleColor,
                        startAngle = rotation - 90f,
                        sweepAngle = 92f,
                        useCenter = false,
                        topLeft = ringTopLeft,
                        size = ringSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawPowerGlyph(PrimaryIdleColor)
                }
                is ConnectionState.Connected -> {
                    if (connectedFill > 0f) {
                        drawCircle(ConnectedColor.copy(alpha = 0.2f + connectedFill * 0.8f), radius = size.minDimension * 0.5f * connectedFill)
                    }
                    drawCircle(ConnectedColor, style = Stroke(stroke))
                    drawShieldCheck(CanvasColor)
                }
                is ConnectionState.Failed -> {
                    drawCircle(ErrorColor, style = Stroke(stroke))
                    drawFailureGlyph(ErrorColor)
                }
            }
        }
    }
}

private fun DrawScope.drawPowerGlyph(color: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val stroke = 3.dp.toPx()
    drawLine(
        color,
        Offset(center.x, center.y - 31.dp.toPx()),
        Offset(center.x, center.y - 3.dp.toPx()),
        stroke,
        StrokeCap.Round,
    )
    val arcSize = 62.dp.toPx()
    drawArc(
        color,
        startAngle = -42f,
        sweepAngle = 264f,
        useCenter = false,
        topLeft = Offset(center.x - arcSize / 2, center.y - arcSize / 2),
        size = Size(arcSize, arcSize),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawShieldCheck(color: Color) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val path = Path().apply {
        moveTo(c.x, c.y - 35.dp.toPx())
        lineTo(c.x - 29.dp.toPx(), c.y - 23.dp.toPx())
        lineTo(c.x - 26.dp.toPx(), c.y + 8.dp.toPx())
        quadraticTo(c.x - 18.dp.toPx(), c.y + 30.dp.toPx(), c.x, c.y + 38.dp.toPx())
        quadraticTo(c.x + 18.dp.toPx(), c.y + 30.dp.toPx(), c.x + 26.dp.toPx(), c.y + 8.dp.toPx())
        lineTo(c.x + 29.dp.toPx(), c.y - 23.dp.toPx())
        close()
    }
    drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    drawLine(color, Offset(c.x - 13.dp.toPx(), c.y + 1.dp.toPx()), Offset(c.x - 3.dp.toPx(), c.y + 12.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    drawLine(color, Offset(c.x - 3.dp.toPx(), c.y + 12.dp.toPx()), Offset(c.x + 16.dp.toPx(), c.y - 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawFailureGlyph(color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val delta = 21.dp.toPx()
    val stroke = 3.dp.toPx()
    drawLine(color, Offset(c.x - delta, c.y - delta), Offset(c.x + delta, c.y + delta), stroke, StrokeCap.Round)
    drawLine(color, Offset(c.x + delta, c.y - delta), Offset(c.x - delta, c.y + delta), stroke, StrokeCap.Round)
}

@Composable
private fun ConnectionSelector(connection: DisplayedConnection, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .border(1.dp, if (enabled) DividerColor else DividerColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolGlyph(protocol = connection.protocol, color = if (enabled) PrimaryIdleColor else MutedColor.copy(alpha = 0.55f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (connection.isRuntimeActive) R.string.active_connection else R.string.selected_connection),
                style = MaterialTheme.typography.bodySmall,
                color = MutedColor,
            )
            Text(
                text = connection.name?.let { name ->
                    connection.protocol?.let { "$name · ${it.displayName}" } ?: name
                } ?: stringResource(R.string.no_config),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) InkColor else MutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = if (enabled) MutedColor else DividerColor)
    }
}

@Composable
private fun ProtocolGlyph(protocol: ProtocolType?, color: Color) {
    val label = stringResource(R.string.current_protocol_icon, protocol?.displayName ?: "VPN")
    Canvas(Modifier.size(28.dp).semantics { contentDescription = label }) {
        val stroke = 1.5.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = 10.dp.toPx(), style = Stroke(stroke))
        if (protocol == ProtocolType.VLESS) {
            val p = Path().apply {
                moveTo(c.x - 6.dp.toPx(), c.y - 5.dp.toPx())
                lineTo(c.x, c.y + 6.dp.toPx())
                lineTo(c.x + 6.dp.toPx(), c.y - 5.dp.toPx())
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawLine(color, Offset(c.x - 5.dp.toPx(), c.y), Offset(c.x + 5.dp.toPx(), c.y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun LiveFacts(stats: TunnelStats) {
    val connectedAt = stats.connectedAtEpochMs
    val elapsed by produceState(initialValue = 0L, key1 = connectedAt) {
        while (connectedAt != null) {
            value = ((System.currentTimeMillis() - connectedAt).coerceAtLeast(0L)) / 1_000L
            delay(1_000)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Fact(stringResource(R.string.exit_ip), exitIpText(stats), Modifier.weight(1f))
            Fact(
                stringResource(R.string.traffic),
                pairOrDash(stats.rxBytes, stats.txBytes) { formatBytes(it) },
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Fact(
                stringResource(R.string.duration),
                if (connectedAt == null) stringResource(R.string.not_available) else formatDuration(elapsed),
                Modifier.weight(1f),
            )
            Fact(
                stringResource(R.string.live_speed),
                speedText(stats),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MutedColor, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = InkColor, maxLines = 1)
    }
}

@Composable
private fun QuickNavigation(onSettingsClick: () -> Unit, onLogsClick: () -> Unit, onConfigsClick: () -> Unit) {
    // Do not force a short height: Material's NavigationBar already accounts
    // for gesture/three-button system insets. The previous 72dp constraint
    // clipped and vertically shifted icons on real devices.
    NavigationBar(containerColor = SurfaceColor, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryIdleColor,
            selectedTextColor = InkColor,
            unselectedIconColor = MutedColor,
            unselectedTextColor = MutedColor,
            indicatorColor = SurfaceVariantColor,
        )
        NavigationBarItem(
            selected = false,
            onClick = onSettingsClick,
            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) },
            label = { Text(stringResource(R.string.settings), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = colors,
        )
        NavigationBarItem(
            selected = false,
            onClick = onLogsClick,
            icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.logs)) },
            label = { Text(stringResource(R.string.logs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = colors,
        )
        NavigationBarItem(
            selected = false,
            onClick = onConfigsClick,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.configs)) },
            label = { Text(stringResource(R.string.configs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = colors,
        )
    }
}

@Composable
private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
    is ConnectionState.Connecting -> stringResource(R.string.status_connecting)
    is ConnectionState.Connected -> stringResource(R.string.status_connected)
    is ConnectionState.Failed -> stringResource(R.string.status_failed)
    is ConnectionState.Switching -> stringResource(R.string.status_switching)
}

private fun stateColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Connected -> ConnectedColor
    is ConnectionState.Failed -> ErrorColor
    else -> PrimaryIdleColor
}

@Composable
private fun exitIpText(stats: TunnelStats): String {
    val ip = stats.exitIp ?: return stringResource(R.string.not_available)
    val flag = countryFlag(stats.countryCode)
    return if (flag == null) ip else "$flag  $ip"
}

@Composable
private fun speedText(stats: TunnelStats): String {
    val down = stats.rxBytesPerSecond
    val up = stats.txBytesPerSecond
    if (down == null || up == null) return stats.latencyMs?.let {
        stringResource(R.string.latency_only, it)
    } ?: stringResource(R.string.not_available)
    val speed = stringResource(R.string.speed_pair, formatBytes(down), formatBytes(up))
    return stats.latencyMs?.let { stringResource(R.string.speed_with_latency, speed, it) } ?: speed
}

@Composable
private fun <T> pairOrDash(first: T?, second: T?, transform: (T) -> String): String {
    if (first == null || second == null) return stringResource(R.string.not_available)
    return stringResource(R.string.bytes_pair, transform(first), transform(second))
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    if (safe < 1_024) return "$safe B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = safe.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit++
    } while (value >= 1_024 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun countryFlag(countryCode: String?): String? {
    val code = countryCode?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: return null
    val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
