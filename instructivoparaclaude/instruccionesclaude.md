INSTRUCCIONES DE CONTEXTO PARA CLAUDE - PROYECTO ASOMBRATE

Objetivo
Este archivo contiene el contexto estable del proyecto para evitar repetirlo en cada prompt.
Úsalo como base antes de proponer o aplicar cambios.

Resumen del proyecto

- Proyecto Android Studio con Kotlin y Jetpack Compose (Material 3).
- App: Asombrate.
- Caso de uso principal: recomendar de qué lado sentarse durante un trayecto para tener más sombra.
- Flujo general:

1. Usuario selecciona origen y destino.
2. App consulta OpenRouteService para sugerencias, reverse geocoding y ruta.
3. Se decodifica la polyline de la ruta.
4. Se calcula el lado de sombra por segmento usando posición solar.
5. Se muestra recomendación final en UI Compose.

Stack técnico

- Kotlin 2.0.21
- Android Gradle Plugin 8.13.2
- compileSdk 36, targetSdk 36, minSdk 24
- Jetpack Compose + Material3
- Retrofit + Gson
- osmdroid para mapa
- commons-suncalc para cálculo solar
- Inyección de API key de ORS por BuildConfig.ORS_API_KEY

Arquitectura y archivos clave

- Entrada UI:
  MainActivity.kt
- Estado y lógica principal:
  ShadowViewModel.kt
- UI de selección de ubicación y mapa:
  LocationPickerUI.kt
- Cliente/API OpenRouteService:
  ShadowService.kt
- Modelos de dominio/API/estado:
  ShadowModels.kt
- Utilidades geoespaciales y de sombra:
  ShadowUtils.kt
- Configuración Android y permisos:
  AndroidManifest.xml
- Dependencias y versiones:
  build.gradle.kts
  libs.versions.toml

Reglas para cambios de código

- No romper el flujo actual de origen/destino, confirmación de mapa y cálculo de sombra.
- Mantener estilo Compose actual, evitando reestructuras grandes no solicitadas.
- Priorizar cambios mínimos y localizados.
- No mover archivos ni renombrar símbolos públicos sin justificación clara.
- Evitar introducir nuevas librerías salvo necesidad real.
- Conservar compatibilidad con minSdk 24.
- No exponer secretos: nunca hardcodear API keys.
- Respetar nombres y paquete actual: com.example.asombrate.

Estrategia de eficiencia de tokens

- Responder en formato breve y accionable.
- Leer solo archivos necesarios para la tarea.
- Proponer primero un plan de 3 a 5 pasos máximo.
- Mostrar únicamente diffs o bloques modificados, no archivos completos.
- Evitar explicaciones largas de conceptos básicos.
- Si hay supuestos, listarlos en una sola sección corta.
- Si falta contexto crítico, hacer máximo 2 preguntas puntuales antes de editar.
- No repetir el contexto de este archivo en cada respuesta.

Formato de salida esperado en cada tarea

- Sección 1: Plan breve.
- Sección 2: Cambios por archivo.
- Sección 3: Riesgos o regresiones posibles.
- Sección 4: Validación (comandos y resultado esperado).

Comandos de validación recomendados

- Windows:
  gradlew.bat :app:assembleDebug
  gradlew.bat :app:testDebugUnitTest
- macOS/Linux:
  ./gradlew :app:assembleDebug
  ./gradlew :app:testDebugUnitTest

Estado actual (actualizado)

- Ya existe plano de asientos por vehículo (Auto 2x2 y Autobús 10x4).
- Ya existe cálculo por asiento con exposición estimada al sol ponderada por distancia.
- Ya existe recomendación automática por asiento + top de alternativas.
- Ya existe fallback cuando no hay datos solares suficientes.
- Ya existen assets de asientos y carrocería en drawable para render visual.
- Ya existen pruebas unitarias para el calculador de exposición de asientos.

Backlog priorizado para siguientes iteraciones

Fase 1 - Fuente única de verdad en estado/UI

- Evitar recalcular planes de asientos en Composables si ya están en estado del ViewModel.
- Concentrar recomendación y plan final en el estado de éxito.
- Reducir duplicidad de lógica entre UI y capa de estado.

Fase 2 - Robustez de red y control de consumo ORS

- Manejar errores HTTP con mayor detalle (429, 5xx, timeouts).
- Implementar reintentos acotados para fallos transitorios.
- Reducir llamadas repetidas con cache liviano para geocode y reverse geocode.
- Evitar solicitudes innecesarias por movimientos pequeños de mapa.

Fase 3 - Explicabilidad y confianza de recomendación

- Mostrar nivel de confianza (alta/media/baja) para la recomendación.
- Explicar brevemente por qué se eligió un asiento.
- Mejorar el texto de fallback cuando no hay suficientes datos.

Fase 4 - Accesibilidad e internacionalización

- Mover textos hardcodeados a resources strings.
- Reforzar semántica para lector de pantalla.
- Verificar targets táctiles y contraste visual en asientos/leyendas.

Fase 5 - Calidad de pruebas end-to-end de presentación

- Agregar pruebas de ViewModel para flujos de éxito, fallback y error.
- Agregar pruebas de UI Compose para selección, recomendado, cambio de vehículo y alternativas.
- Mantener y extender cobertura sin romper pruebas existentes.
