# Asombrate

Aplicación Android (Kotlin + Jetpack Compose) que recomienda en qué asiento sentarte durante un trayecto para recibir menos sol.

La app calcula exposición solar a lo largo de una ruta real y la proyecta en un mapa de asientos para Auto y Autobús.

## 1. Objetivo

Asombrate busca responder esta pregunta de forma práctica:

"¿En qué asiento me conviene sentarme para evitar el sol durante este viaje?"

La recomendación se basa en:

- Ruta real entre origen y destino.
- Posición solar por segmento de ruta.
- Tipo de vehículo y distribución de asientos.

## 2. Funcionalidades actuales

### 2.1 Búsqueda y selección de ubicaciones

- Origen y destino por texto (geocoding ORS).
- Ajuste manual en mapa (osmdroid).
- Reverse geocoding al mover mapa.
- Botón "usar mi ubicación actual" para origen o destino.

### 2.2 Cálculo de recomendación

- Decodificación de polyline de la ruta.
- Cálculo del lado sombreado por segmento usando SunCalc.
- Ponderación por distancia real (haversine) en metros.
- Recomendación por asiento con alternativas.

### 2.3 Plano de asientos

- Auto: 2x2.
- Autobús: 10x4 con pasillo central.
- Estados visuales: SUN, SHADE, PARTIAL, NEUTRAL, SELECTED.
- Indicador de asiento recomendado + selección manual del usuario.
- Animaciones y leyenda de estados.

### 2.4 Explicabilidad y confianza

- Porcentaje de exposición estimada del asiento recomendado.
- Cobertura de datos solares del trayecto.
- Nivel de confianza: HIGH, MEDIUM, LOW, NONE.
- Fallback explícito cuando no hay señal suficiente.

### 2.5 Robustez de red

- Timeouts de red en OkHttp.
- Reintentos con backoff exponencial para errores transitorios.
- Cache TTL para geocode, reverse geocode y directions.
- Umbral de movimiento (25m) para evitar llamadas redundantes de reverse geocode.

### 2.6 Mensajes contextuales

- Error amistoso cuando no hay sol (ejemplo: de noche).
- Mensajes de red y servicio centralizados.

## 3. Stack técnico

- Kotlin 2.0.21
- Android Gradle Plugin 8.13.2
- compileSdk 36, targetSdk 36, minSdk 24
- Jetpack Compose + Material3
- Retrofit + Gson + OkHttp
- osmdroid
- commons-suncalc
- Google Play Services Location

## 4. Arquitectura

Arquitectura por capas, con fuente única de verdad en estado (ViewModel):

- UI Compose: pantallas, interacción, accesibilidad, render de estados.
- ViewModel: orquestación del flujo, estado UI, cachés, errores y llamadas de red.
- Cálculo puro: recomendación solar y ranking de asientos (sin dependencias Android).
- Red: cliente ORS con Retrofit.

Flujo simplificado:

1. Usuario define origen, destino y hora.
2. ViewModel obtiene ruta (o usa cache).
3. ShadowCalculation genera recomendaciones por vehículo.
4. UI renderiza resultados sin recalcular negocio.

## 5. Archivos clave

- app/src/main/java/com/example/asombrate/MainActivity.kt
  - Pantalla principal Compose y secciones de recomendación/plano.

- app/src/main/java/com/example/asombrate/ShadowViewModel.kt
  - Estado global, permisos de ubicación, llamadas ORS, cache, cálculo final.

- app/src/main/java/com/example/asombrate/ShadowCalculation.kt
  - Ensamble de recomendación final y métricas de confianza.

- app/src/main/java/com/example/asombrate/SeatExposureCalculator.kt
  - Cálculo de exposición por asiento y ranking.

- app/src/main/java/com/example/asombrate/NetworkUtils.kt
  - TTL cache, clasificación de errores, retries y umbral de movimiento.

- app/src/main/java/com/example/asombrate/ShadowService.kt
  - Definición de APIs ORS y cliente Retrofit/OkHttp.

- app/src/main/java/com/example/asombrate/LocationPickerUI.kt
  - UI de búsqueda/selección de ubicación y mapa.

- app/src/main/java/com/example/asombrate/SeatMapUI.kt
  - UI y animaciones del plano de asientos.

- app/src/main/java/com/example/asombrate/ShadowUtils.kt
  - Polyline, distancia, bearing, validación de sol y lado de sombra.

- app/src/main/res/values/strings.xml
  - Textos localizados y accesibilidad.

## 6. Requisitos para desarrollo

- Android Studio (recomendado estable reciente).
- JDK 11.
- SDK Android con API 36.

## 7. Configuración local y de producción

La app Android consume un backend gateway propio (no llama ORS directo en producción).

1. Crear o editar `local.properties` en la raíz del proyecto.
2. Configurar URLs por entorno:

```properties
BACKEND_BASE_URL_DEBUG=http://10.0.2.2:8081/
BACKEND_BASE_URL_RELEASE=https://tu-backend-publico.com/
```

3. Para pruebas locales del backend, la key ORS se carga en el gateway desde entorno o `local.properties`:

```properties
ORS_API_KEY=tu_api_key_de_openrouteservice
```

Importante:

- `local.properties` está ignorado por git.
- No hardcodear ni commitear API keys.
- Builds `release` requieren `BACKEND_BASE_URL_RELEASE` con URL HTTPS real.

## 8. Ejecutar la app

Windows:

```bash
gradlew.bat :app:assembleDebug
gradlew.bat :app:installDebug
```

macOS / Linux:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## 9. Generación de APK para testers

### APK Debug

```bash
gradlew.bat :app:assembleDebug
```

Salida esperada:

- app/build/outputs/apk/debug/app-debug.apk

### APK Release (sin firma final de distribución)

```bash
gradlew.bat :app:assembleRelease
```

Salida esperada:

- app/build/outputs/apk/release/app-release-unsigned.apk

Para subir a usuarios finales, firma el APK/AAB con keystore de release.

### Instalación manual con ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 10. Pruebas

### Unit tests

```bash
gradlew.bat :app:testDebugUnitTest
```

Cobertura principal:

- ShadowCalculationTest
- SeatExposureCalculatorTest
- NetworkUtilsTest
- ShadowViewModelTest

### Instrumentation tests (dispositivo/emulador)

```bash
gradlew.bat :app:connectedDebugAndroidTest
```

Incluye pruebas Compose de interacción en SeatMapUiTest.

## 11. Permisos

Declarados en AndroidManifest.xml:

- INTERNET
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION

Uso:

- Ubicación: botón "usar mi ubicación actual".
- Internet: ORS geocoding, reverse geocoding y directions.

## 12. Comportamientos importantes del dominio

- Si el sol no está sobre el horizonte, se devuelve un mensaje específico de no-sol.
- Si no hay datos solares suficientes, la recomendación puede pasar a fallback controlado.
- El tipo de vehículo seleccionado se conserva vía SavedStateHandle.

## 13. Troubleshooting rápido

### "API key inválida" o errores 401/403

- Verifica ORS_API_KEY en local.properties.
- Revisa permisos y límites de tu cuenta ORS.

### "No se encontró ruta" o respuesta sin geometría

- Revisa conectividad.
- Prueba con ubicaciones más convencionales o más cercanas.

### Errores de red intermitentes

- La app ya aplica retry/backoff para transitorios (408, 429, 5xx, IO).

### No aparece recomendación clara

- Puede ocurrir en fallback o cobertura solar baja.
- Revisa mensaje de confianza y cobertura en UI.

## 14. Guía para futuros desarrolladores y agentes de IA

### Principios del proyecto

- Evitar lógica de negocio en Composables.
- Mantener cambios mínimos y localizados.
- No romper flujo origen/destino/mapa/cálculo.
- No exponer secretos.

### Dónde tocar cada cosa

- Reglas de recomendación: ShadowCalculation y SeatExposureCalculator.
- Red y resiliencia: ShadowService y NetworkUtils.
- Render/UX: MainActivity, LocationPickerUI, SeatMapUI.
- Textos: strings.xml.

### Contexto operativo adicional

Existe documentación interna para prompts y contexto en:

- instructivoparaclaude/instruccionesclaude.md

Recomendación:

- Leer ese archivo antes de modificaciones grandes o automatizadas.

## 15. Estado del proyecto

Proyecto activo en evolución, con foco en:

- precisión de recomendación por asiento,
- explicabilidad de resultados,
- calidad de UX/accesibilidad,
- robustez para uso real en móvil.
