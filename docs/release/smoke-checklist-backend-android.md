# Smoke Checklist Backend-Android (Asombrate)

Fecha: 2026-04-25

## Configuración

- Backend release: `https://asombrate-backend.onrender.com`
- Backend local debug: `http://10.0.2.2:8081/`

## Pre-checks

- `GET /healthz` = `200`
- `GET /readyz` = `200`
- `gradlew.bat :app:assembleDebug` completado

## Escenarios

1. Flujo normal
- Buscar origen.
- Buscar destino.
- Calcular recomendación.
- Esperado: resultado visible y sin mensajes técnicos crudos.

2. Ubicación actual
- Tocar "usar mi ubicación actual".
- Esperado: si hay permiso, el campo se actualiza; si falla, aparece mensaje claro.

3. Error geocode o reverse geocode
- Apagar red o apuntar debug al backend local apagado.
- Esperado: mensaje visible en el campo, sin crash.

4. `HIGH_USAGE`
- Forzar umbral bajo temporalmente en backend.
- Esperado: banner preventivo y cálculo exitoso si todavía hay presupuesto.

5. `DEGRADED`
- Sembrar cache y luego forzar degradado.
- Esperado: misma ruta responde desde cache y la app lo comunica.

6. `BLOCK`
- Forzar cuota agotada.
- Esperado: `429`, mensaje amigable y sin ambigüedad con "sin Internet".

## Criterio de salida

- Contrato backend-app consistente.
- Sin crash visible.
- Mensajes de error claros para testers.
