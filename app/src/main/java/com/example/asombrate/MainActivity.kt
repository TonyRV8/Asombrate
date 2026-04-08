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
    var departureTimeString by remember { mutableStateOf("Ahora") }

    val uiState by viewModel.uiState.collectAsState()
    val originState by viewModel.originState.collectAsState()
    val destinationState by viewModel.destinationState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Asómbrate", fontWeight = FontWeight.Bold) },
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
                        label = "Origen",
                        placeholder = "Ej: Bellas Artes",
                        leadingIcon = Icons.Default.Home
                    )

                    LocationPickerUI(
                        state = destinationState,
                        onQueryChanged = viewModel::onDestinationQueryChanged,
                        onSuggestionSelected = viewModel::onDestinationSelected,
                        onMapMoved = viewModel::onDestinationMapMoved,
                        onMapConfirmed = viewModel::onDestinationMapConfirmed,
                        label = "Destino",
                        placeholder = "Ej: Ángel de la Independencia",
                        leadingIcon = Icons.Default.Search
                    )

                    OutlinedTextField(
                        value = departureTimeString,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Hora de salida") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
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

            Button(
                onClick = { viewModel.calculateShadow() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = uiState !is ShadowState.Loading
            ) {
                if (uiState is ShadowState.Loading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Calcular Sombra", fontSize = 18.sp)
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
                        routeProfile = state.routeProfile,
                        shadySide = state.shadySide,
                        shadePercent = state.shadePercent
                    )
                    DebugInfoCard(state.debugInfo)
                }
                else -> {}
            }
        }
    }
}

@Composable
fun ErrorDebugCard(message: String, debugInfo: String?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(message, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
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
            Text("Información Técnica (Debug)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(debugInfo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ResultsSection(results: List<ShadowResult>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Recomendación de viaje",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        results.forEach { result ->
            ResultCard(result)
        }
    }
}

@Composable
fun SeatMapSection(
    routeProfile: RouteSolarProfile?,
    shadySide: String?,
    shadePercent: Int
) {
    var vehicleType by remember { mutableStateOf(VehicleType.BUS) }
    var selectedSeatId by remember(routeProfile, shadySide, shadePercent) {
        mutableStateOf<String?>(null)
    }

    val result = remember(vehicleType, routeProfile, shadySide, shadePercent) {
        if (routeProfile != null && routeProfile.validDistanceMeters > 0.0) {
            SeatExposureCalculator.buildPlan(routeProfile, vehicleType)
        } else {
            SeatExposureCalculator.fallbackPlan(vehicleType, shadySide, shadePercent)
        }
    }
    val plan = result.plan
    val recommendedId = result.recommendedSeatId
    val recommendedSeat = plan.seats.firstOrNull { it.id == recommendedId }
    val selectedSeat = plan.seats.firstOrNull { it.id == selectedSeatId }

    AnimatedVisibility(
        visible = shadySide != null || routeProfile != null,
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
                    "Plano de asientos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )

                VehicleTypeSelector(
                    current = vehicleType,
                    onSelected = {
                        vehicleType = it
                        selectedSeatId = null
                    },
                    modifier = Modifier.align(Alignment.Start)
                )

                SeatMap(
                    plan = plan,
                    selectedSeatId = selectedSeatId,
                    recommendedSeatId = recommendedId,
                    onSeatClick = { seat -> selectedSeatId = seat.id },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (vehicleType == VehicleType.BUS) 520.dp else 240.dp)
                )

                RecommendationBadge(
                    recommendedSeat = recommendedSeat,
                    selectedSeat = selectedSeat,
                    shadySide = shadySide,
                    modifier = Modifier.fillMaxWidth()
                )

                if (result.alternatives.isNotEmpty()) {
                    AlternativesPills(
                        alternatives = result.alternatives,
                        onPick = { id -> selectedSeatId = id },
                        modifier = Modifier.align(Alignment.Start)
                    )
                }

                SeatLegend(modifier = Modifier.align(Alignment.Start))
            }
        }
    }
}

@Composable
private fun RecommendationBadge(
    recommendedSeat: Seat?,
    selectedSeat: Seat?,
    shadySide: String?,
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "★",
                fontSize = 22.sp,
                color = Color(0xFFFFC107)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (recommendedSeat != null) {
                    val pct = (recommendedSeat.exposure * 100).toInt()
                    Text(
                        "Recomendado: ${SeatIds.readable(recommendedSeat)}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "${pct}% de exposición solar estimada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                } else if (shadySide != null) {
                    Text(
                        "Toca un asiento del lado $shadySide",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Text(
                        "Sin datos suficientes para recomendar asiento",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (selectedSeat != null && selectedSeat.id != recommendedSeat?.id) {
                    Spacer(Modifier.height(6.dp))
                    val pct = (selectedSeat.exposure * 100).toInt()
                    Text(
                        "Tu elección: ${SeatIds.readable(selectedSeat)} (${pct}% sol)",
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
            "Alternativas",
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
                Text(result.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(result.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
