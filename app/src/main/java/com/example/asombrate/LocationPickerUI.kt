package com.example.asombrate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@Composable
fun LocationPickerUI(
    state: LocationFieldState,
    onQueryChanged: (String) -> Unit,
    onSuggestionSelected: (LocationSuggestion) -> Unit,
    onMapMoved: (lat: Double, lng: Double) -> Unit,
    onMapConfirmed: () -> Unit,
    onMyLocationClicked: () -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Campo de texto con indicador de reverse geocoding
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(leadingIcon, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.a11y_clear_text)
                            )
                        }
                    }
                    IconButton(onClick = onMyLocationClicked) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = stringResource(R.string.a11y_my_location),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    when {
                        state.isSearching || state.isReverseGeocoding ->
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        state.confirmed != null ->
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Lista de sugerencias de texto
        AnimatedVisibility(visible = state.suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(state.suggestions) { suggestion ->
                        SuggestionItem(
                            suggestion = suggestion,
                            onClick = { onSuggestionSelected(suggestion) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        // Mapa interactivo con pin fijo al centro
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            // Mapa osmdroid
            InteractiveMapView(
                lat = state.mapLat,
                lng = state.mapLng,
                flyToVersion = state.flyToVersion,
                onMapMoved = onMapMoved,
                modifier = Modifier.fillMaxSize()
            )

            // Pin fijo al centro (mira telescópica estilo Uber)
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = stringResource(R.string.map_center_pin_desc),
                tint = Color(0xFFE53935),
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center)
                    .offset(y = (-20).dp)  // Offset para que la punta toque el centro
            )

            // Indicador de cargando reverse geocode
            if (state.isReverseGeocoding) {
                val loadingDesc = stringResource(R.string.loading_reverse_geocode)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .semantics { contentDescription = loadingDesc },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Botón "Confirmar ubicación" flotante
            Button(
                onClick = onMapConfirmed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = !state.isReverseGeocoding
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = if (state.confirmed != null)
                        stringResource(R.string.btn_location_confirmed)
                    else
                        stringResource(R.string.btn_confirm_location),
                    modifier = Modifier.padding(start = 6.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SuggestionItem(suggestion: LocationSuggestion, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = suggestion.label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun InteractiveMapView(
    lat: Double,
    lng: Double,
    flyToVersion: Int,
    onMapMoved: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Flag para ignorar scroll events generados por animación programática
    var ignoringProgrammaticScroll by remember { mutableStateOf(false) }
    var lastFlyToVersion by remember { mutableIntStateOf(flyToVersion) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(lat, lng))

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    if (ignoringProgrammaticScroll) return true
                    val center = mapCenter
                    onMapMoved(center.latitude, center.longitude)
                    return true
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    return true
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            // Solo animar cuando flyToVersion cambia (selección de texto)
            if (flyToVersion != lastFlyToVersion) {
                lastFlyToVersion = flyToVersion
                ignoringProgrammaticScroll = true
                val point = GeoPoint(lat, lng)
                view.controller.animateTo(point, 16.0, 800)
                // Quitar el flag después de que termine la animación
                view.postDelayed({ ignoringProgrammaticScroll = false }, 900)
            }
        }
    )
}
