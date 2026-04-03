package com.example.asombrate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.asombrate.ui.theme.AsombrateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsombrateTheme {
                ShadowCalculatorScreen()
            }
        }
    }
}

// Modelo de datos simulado para los resultados
data class ShadowResult(
    val title: String, 
    val description: String, 
    val icon: ImageVector, 
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowCalculatorScreen() {
    // ESTADO (State Hoisting)
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var departureTime by remember { mutableStateOf("04:00 PM") }
    var results by remember { mutableStateOf<List<ShadowResult>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }

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
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECCIÓN DE ENTRADA
            InputForm(
                origin = origin,
                onOriginChange = { origin = it },
                destination = destination,
                onDestinationChange = { destination = it },
                departureTime = departureTime
            )

            // BOTÓN DE ACCIÓN
            Button(
                onClick = {
                    results = getMockResults()
                    showResults = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Calcular Sombra", fontSize = 18.sp)
            }

            // SECCIÓN DE RESULTADOS
            AnimatedVisibility(
                visible = showResults,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 })
            ) {
                ResultsSection(results)
            }
        }
    }
}

@Composable
fun InputForm(
    origin: String,
    onOriginChange: (String) -> Unit,
    destination: String,
    onDestinationChange: (String) -> Unit,
    departureTime: String
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp), 
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = origin,
                onValueChange = onOriginChange,
                label = { Text("Origen") },
                placeholder = { Text("¿Desde dónde sales?") },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = destination,
                onValueChange = onDestinationChange,
                label = { Text("Destino") },
                placeholder = { Text("¿A dónde vas?") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = departureTime,
                onValueChange = {},
                readOnly = true,
                label = { Text("Hora de salida") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
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
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(results) { result ->
                ResultCard(result)
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

fun getMockResults(): List<ShadowResult> {
    return listOf(
        ShadowResult(
            "Siéntate del lado derecho",
            "Es el lado con mayor sombra durante el trayecto.",
            Icons.Default.Check,
            Color(0xFF4CAF50)
        ),
        ShadowResult(
            "Riesgo solar alto",
            "Entre 4:20 y 4:45 pm el sol pegará de frente.",
            Icons.Default.Info,
            Color(0xFFFF9800)
        )
    )
}
