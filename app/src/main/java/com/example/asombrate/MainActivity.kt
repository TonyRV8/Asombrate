package com.example.asombrate

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.asombrate.ui.theme.AsombrateTheme
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private val viewModel: ShadowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsombrateTheme {
                ShadowCalculatorScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowCalculatorScreen(viewModel: ShadowViewModel) {
    val defaultDeparture = stringResource(R.string.departure_now)
    var departureTimeString by remember { mutableStateOf(defaultDeparture) }

    val uiState by viewModel.uiState.collectAsState()
    val originState by viewModel.originState.collectAsState()
    val destinationState by viewModel.destinationState.collectAsState()
    val selectedVehicle by viewModel.selectedVehicle.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card con los campos de ubicación y hora
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LocationPickerUI(
                        state = originState,
                        onQueryChanged = viewModel::onOriginQueryChanged,
                        onSuggestionSelected = viewModel::onOriginSelected,
                        onMapMoved = viewModel::onOriginMapMoved,
                        onMapConfirmed = viewModel::onOriginMapConfirmed,
                        label = stringResource(R.string.label_origin),
                        placeholder = stringResource(R.string.placeholder_origin),
                        leadingIcon = Icons.Default.Home
                    )

                    LocationPickerUI(
                        state = destinationState,
                        onQueryChanged = viewModel::onDestinationQueryChanged,
                        onSuggestionSelected = viewModel::onDestinationSelected,
                        onMapMoved = viewModel::onDestinationMapMoved,
                        onMapConfirmed = viewModel::onDestinationMapConfirmed,
                        label = stringResource(R.string.label_destination),
                        placeholder = stringResource(R.string.placeholder_destination),
                        leadingIcon = Icons.Default.Search
                    )

                    OutlinedTextField(
                        value = departureTimeString,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_departure_time)) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                val calendar = Calendar.getInstance()
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                                        calendar.set(Calendar.MINUTE, minute)
                                        departureTimeString = String.format("%02d:%02d", hour, minute)
                                        viewModel.updateDepartureTime(calendar)
                                    },
                                    calendar.get(Calendar.HOUR_OF_DAY),
                                    calendar.get(Calendar.MINUTE),
                                    true
                                ).show()
                            },
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            val calcA11y = stringResource(R.string.a11y_calculate_button)
            Button(
                onClick = { viewModel.calculateShadow() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = calcA11y },
                shape = RoundedCornerShape(12.dp),
                enabled = uiState !is ShadowState.Loading
            ) {
                if (uiState is ShadowState.Loading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_calculate_shadow), fontSize = 18.sp)
                }
            }

            // Resultados y debug
            when (val state = uiState) {
                is ShadowState.Error -> {
                    ErrorDebugCard(state.message, state.debugInfo)
                }
                is ShadowState.Success -> {
                    ResultsSection(state.results)
                    SeatMapSection(
                        recommendations = state.recommendations,
                        shadySide = state.shadySide,
                        selectedVehicle = selectedVehicle,
                        onVehicleSelected = viewModel::selectVehicle
                    )
                    DebugInfoCard(state.debugInfo)
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun resolveUiText(text: UiText): String {
    return if (text.formatArgs.isEmpty()) {
        stringResource(text.resId)
    } else {
        stringResource(text.resId, *text.formatArgs.toTypedArray())
    }
}

@Composable
fun ErrorDebugCard(message: UiText, debugInfo: String?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    resolveUiText(message),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (!debugInfo.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    debugInfo,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun DebugInfoCard(debugInfo: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.debug_section_title),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(debugInfo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ResultsSection(results: List<ShadowResult>) {
    if (results.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.section_recommendation),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { heading() }
        )
        results.forEach { result ->
            ResultCard(result)
        }
    }
}

@Composable
fun SeatMapSection(
    recommendations: Map<VehicleType, VehicleRecommendation>,
    shadySide: String?,
    selectedVehicle: VehicleType,
    onVehicleSelected: (VehicleType) -> Unit
) {
    // Estado puramente de UI: asiento que el usuario toca.
    var selectedSeatId by remember(recommendations) { mutableStateOf<String?>(null) }

    val currentRec = recommendations[selectedVehicle]
    val plan = currentRec?.seatResult?.plan
    val recommendedId = currentRec?.seatResult?.recommendedSeatId
    val recommendedSeat = plan?.seats?.firstOrNull { it.id == recommendedId }
    val selectedSeat = plan?.seats?.firstOrNull { it.id == selectedSeatId }
    val alternatives = currentRec?.seatResult?.alternatives.orEmpty()
    val explanation = currentRec?.explanation

    AnimatedVisibility(
        visible = plan != null && (shadySide != null || recommendedId != null),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.section_seat_map),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .semantics { heading() }
                )

                val vehicleSelectorDesc = stringResource(R.string.a11y_vehicle_selector)
                VehicleTypeSelector(
                    current = selectedVehicle,
                    onSelected = {
                        onVehicleSelected(it)
                        selectedSeatId = null
                    },
                    modifier = Modifier
                        .align(Alignment.Start)
                        .semantics { contentDescription = vehicleSelectorDesc }
                )

                if (plan != null) {
                    val mapDesc = stringResource(
                        R.string.a11y_seat_map_region,
                        stringResource(selectedVehicle.labelRes)
                    )
                    SeatMap(
                        plan = plan,
                        selectedSeatId = selectedSeatId,
                        recommendedSeatId = recommendedId,
                        onSeatClick = { seat -> selectedSeatId = seat.id },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (selectedVehicle == VehicleType.BUS) 520.dp else 240.dp)
                            .semantics { contentDescription = mapDesc }
                    )
                }

                val recRegion = stringResource(R.string.a11y_recommendation_region)
                RecommendationBadge(
                    recommendedSeat = recommendedSeat,
                    selectedSeat = selectedSeat,
                    shadySide = shadySide,
                    explanation = explanation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = recRegion }
                )

                if (alternatives.isNotEmpty()) {
                    val altDesc = stringResource(R.string.a11y_alternatives_region)
                    AlternativesPills(
                        alternatives = alternatives,
                        onPick = { id -> selectedSeatId = id },
                        modifier = Modifier
                            .align(Alignment.Start)
                            .semantics { contentDescription = altDesc }
                    )
                }

                val legendDesc = stringResource(R.string.a11y_legend_region)
                SeatLegend(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .semantics { contentDescription = legendDesc }
                )
            }
        }
    }
}

@Composable
private fun RecommendationBadge(
    recommendedSeat: Seat?,
    selectedSeat: Seat?,
    shadySide: String?,
    explanation: RecommendationExplanation?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "★",
                fontSize = 22.sp,
                color = Color(0xFFFFC107)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                when {
                    recommendedSeat != null -> {
                        Text(
                            stringResource(
                                R.string.recommended_seat_format,
                                SeatIds.readable(recommendedSeat)
                            ),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            stringResource(
                                R.string.exposure_percent_format,
                                (recommendedSeat.exposure * 100).toInt()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    shadySide != null -> {
                        Text(
                            stringResource(R.string.touch_seat_prompt, shadySide),
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    else -> {
                        Text(
                            stringResource(R.string.no_data_to_recommend),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Fase 3: explicabilidad
                if (explanation != null && recommendedSeat != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.coverage_format,
                            explanation.coveragePercent
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    ConfidenceBadge(
                        confidence = explanation.confidence,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (explanation.isFallback) {
                        Text(
                            stringResource(R.string.fallback_notice),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                if (selectedSeat != null && selectedSeat.id != recommendedSeat?.id) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.your_choice_format,
                            SeatIds.readable(selectedSeat),
                            (selectedSeat.exposure * 100).toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(
    confidence: RecommendationConfidence,
    modifier: Modifier = Modifier
) {
    val (labelRes, symbol, tint) = when (confidence) {
        RecommendationConfidence.HIGH ->
            Triple(R.string.confidence_high, "●●●", Color(0xFF2E7D32))
        RecommendationConfidence.MEDIUM ->
            Triple(R.string.confidence_medium, "●●○", Color(0xFFEF6C00))
        RecommendationConfidence.LOW ->
            Triple(R.string.confidence_low, "●○○", Color(0xFFC62828))
        RecommendationConfidence.NONE ->
            Triple(R.string.confidence_none, "○○○", Color(0xFF607D8B))
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.15f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = symbol,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    R.string.confidence_format,
                    stringResource(R.string.confidence_label),
                    stringResource(labelRes)
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        }
    }
}

@Composable
private fun AlternativesPills(
    alternatives: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.section_alternatives),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            alternatives.forEach { id ->
                AssistChip(
                    onClick = { onPick(id) },
                    label = { Text(id, fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    }
}

@Composable
fun ResultCard(result: ShadowResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = result.color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = result.color,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = result.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    resolveUiText(result.title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(resolveUiText(result.description), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
