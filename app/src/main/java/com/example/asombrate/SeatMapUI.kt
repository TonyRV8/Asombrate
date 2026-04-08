package com.example.asombrate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- Capa visual Fase 3: SVG + microinteracciones ---

private val ColorSun = Color(0xFFFFA726)
private val ColorShade = Color(0xFF546E7A)
private val ColorPartial = Color(0xFFFFD54F)
private val ColorNeutral = Color(0xFFB0BEC5)
private val ColorSelected = Color(0xFF1976D2)
private val ColorRecommendedGlow = Color(0xFFFFC107)

private fun colorForState(state: SeatState): Color = when (state) {
    SeatState.SUN -> ColorSun
    SeatState.SHADE -> ColorShade
    SeatState.PARTIAL -> ColorPartial
    SeatState.NEUTRAL -> ColorNeutral
    SeatState.SELECTED -> ColorSelected
}

/** Glyph Unicode para no depender solo del color (accesibilidad). */
private fun stateGlyph(state: SeatState): String? = when (state) {
    SeatState.SUN -> "☀"
    SeatState.PARTIAL -> "◐"
    SeatState.SHADE -> null
    SeatState.NEUTRAL -> null
    SeatState.SELECTED -> null
}

private fun stateLabel(state: SeatState): String = when (state) {
    SeatState.SUN -> "sol directo"
    SeatState.PARTIAL -> "sol parcial"
    SeatState.SHADE -> "sombra"
    SeatState.NEUTRAL -> "sin datos"
    SeatState.SELECTED -> "seleccionado"
}

@Composable
fun VehicleTypeSelector(
    current: VehicleType,
    onSelected: (VehicleType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VehicleType.values().forEach { type ->
            FilterChip(
                selected = type == current,
                onClick = { onSelected(type) },
                label = { Text(type.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun SeatMap(
    plan: SeatPlan,
    selectedSeatId: String?,
    onSeatClick: (Seat) -> Unit,
    modifier: Modifier = Modifier,
    recommendedSeatId: String? = null
) {
    val vehicleType = plan.vehicleType
    val backdropRes = when (vehicleType) {
        VehicleType.BUS -> R.drawable.ic_bus_body
        VehicleType.CAR -> R.drawable.ic_car_body
    }

    // Stagger de entrada: re-dispara al cambiar vehículo.
    var entranceVisible by remember(vehicleType) { mutableStateOf(false) }
    LaunchedEffect(vehicleType) { entranceVisible = true }

    // Pulse único para toda la malla — compartido en la celda recomendada.
    val infinite = rememberInfiniteTransition(label = "seatPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val seatSize = if (vehicleType == VehicleType.BUS) 34.dp else 56.dp
    val seatSpacing = if (vehicleType == VehicleType.BUS) 4.dp else 10.dp
    val aisleGap = if (vehicleType == VehicleType.BUS) 14.dp else 24.dp
    val horizPadding = if (vehicleType == VehicleType.BUS) 36.dp else 54.dp
    val vertPadding = if (vehicleType == VehicleType.BUS) 70.dp else 56.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = backdropRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )

        Column(
            modifier = Modifier.padding(
                horizontal = horizPadding,
                vertical = vertPadding
            ),
            verticalArrangement = Arrangement.spacedBy(seatSpacing),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (row in 1..vehicleType.rows) {
                val rowDelay = (row - 1) * 40
                AnimatedVisibility(
                    visible = entranceVisible,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = 260,
                            delayMillis = rowDelay
                        )
                    ) + slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(
                            durationMillis = 260,
                            delayMillis = rowDelay
                        )
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(seatSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (col in 1..vehicleType.cols) {
                            val seat = plan.seatAt(row, col) ?: continue
                            val isSelected = seat.id == selectedSeatId
                            val effectiveState =
                                if (isSelected) SeatState.SELECTED else seat.state
                            SeatCell(
                                seat = seat,
                                state = effectiveState,
                                isRecommended = seat.id == recommendedSeatId,
                                pulseAlpha = pulseAlpha,
                                size = seatSize,
                                onClick = { onSeatClick(seat) }
                            )
                            if (vehicleType.aisleAfterCol == col &&
                                col != vehicleType.cols
                            ) {
                                Spacer(Modifier.width(aisleGap))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: Seat,
    state: SeatState,
    isRecommended: Boolean,
    pulseAlpha: Float,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val targetColor = colorForState(state)
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 380),
        label = "seatColor"
    )

    val targetScale = when {
        state == SeatState.SELECTED -> 1.14f
        isRecommended -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "seatScale"
    )

    val description = buildString {
        append("Asiento ${SeatIds.readable(seat)}, ")
        append(stateLabel(state))
        if (seat.exposure > 0.0) append(", ${(seat.exposure * 100).toInt()} por ciento de sol")
        if (isRecommended) append(", recomendado")
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        // Pulse halo para el asiento recomendado
        if (isRecommended) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        color = ColorRecommendedGlow.copy(alpha = pulseAlpha * 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    )
            )
        }

        // Borde de selección / recomendación
        val borderColor = when {
            state == SeatState.SELECTED -> MaterialTheme.colorScheme.primary
            isRecommended -> ColorRecommendedGlow
            else -> Color.Transparent
        }
        val borderWidth = when {
            state == SeatState.SELECTED -> 2.5.dp
            isRecommended -> 2.dp
            else -> 0.dp
        }
        if (borderWidth.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(2.dp)
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            )
        }

        // Asiento SVG tintado según estado
        Image(
            painter = painterResource(id = R.drawable.ic_seat),
            contentDescription = null,
            colorFilter = ColorFilter.tint(animatedColor),
            modifier = Modifier
                .padding(5.dp)
                .fillMaxSize()
        )

        // Etiqueta del id sobre el asiento
        Text(
            text = seat.id,
            fontSize = (size.value * 0.26f).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // Glyph de estado (abajo-izquierda) — accesibilidad no-color
        stateGlyph(state)?.let { glyph ->
            Text(
                text = glyph,
                fontSize = (size.value * 0.28f).sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 3.dp, bottom = 2.dp)
            )
        }

        // Estrella de recomendación (arriba-derecha)
        if (isRecommended) {
            Text(
                text = "★",
                fontSize = (size.value * 0.32f).sp,
                color = ColorRecommendedGlow,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp, top = 1.dp)
            )
        }
    }
}

@Composable
fun SeatLegend(modifier: Modifier = Modifier) {
    val items = listOf(
        LegendEntry("Sol ☀", ColorSun),
        LegendEntry("Parcial ◐", ColorPartial),
        LegendEntry("Sombra", ColorShade),
        LegendEntry("Neutro", ColorNeutral),
        LegendEntry("Selección", ColorSelected),
        LegendEntry("Recomendado ★", ColorRecommendedGlow)
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { LegendChip(it) }
            }
        }
    }
}

private data class LegendEntry(val label: String, val color: Color)

@Composable
private fun LegendChip(entry: LegendEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(entry.color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Previews ---

private fun previewProfile(side: ShadeSide): RouteSolarProfile =
    RouteSolarProfile(
        segments = listOf(RouteSegmentSolar(1500.0, side)),
        totalDistanceMeters = 1500.0,
        validDistanceMeters = 1500.0
    )

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 320, heightDp = 560)
@Composable
private fun PreviewSeatMapBus() {
    val result = SeatExposureCalculator.buildPlan(
        previewProfile(ShadeSide.RIGHT),
        VehicleType.BUS
    )
    SeatMap(
        plan = result.plan,
        selectedSeatId = "5B",
        recommendedSeatId = result.recommendedSeatId,
        onSeatClick = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 340, heightDp = 240)
@Composable
private fun PreviewSeatMapCar() {
    val result = SeatExposureCalculator.buildPlan(
        previewProfile(ShadeSide.LEFT),
        VehicleType.CAR
    )
    SeatMap(
        plan = result.plan,
        selectedSeatId = null,
        recommendedSeatId = result.recommendedSeatId,
        onSeatClick = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewSeatLegend() {
    SeatLegend()
}
