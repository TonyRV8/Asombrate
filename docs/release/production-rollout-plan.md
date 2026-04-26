# Plan de salida a produccion (Asombrate)

Fecha: 2026-04-25

## Open Testing

1. Mantener el backend actual en `https://asombrate-backend.onrender.com`.
2. Ejecutar:
   - `node --test backend/ors-gateway.test.js`
   - `gradlew.bat :app:testDebugUnitTest`
   - `gradlew.bat :app:assembleDebug`
   - `gradlew.bat :app:bundleRelease`
3. Subir el `.aab` al track Open Testing.
4. Invitar testers controlados.
5. Monitorear 24-72 horas:
   - `healthz`
   - `readyz`
   - `429`
   - `5xx`
   - feedback de ubicación y cálculo

## Producción

1. Incrementar `APP_VERSION_CODE`.
2. Repetir builds y smoke.
3. Publicar con rollout gradual.
4. No mover backend ni umbrales el mismo día si no es necesario.

## Señales de rollback

- Aumento anormal de `429`.
- `readyz` deja de responder `200`.
- Fallos repetidos de geocode o directions.
- Quejas de usuarios sobre indisponibilidad o errores TLS.

## Acción de rollback

1. Detener expansión del rollout en Play.
2. Revertir backend al deploy estable previo si el incidente es servidor.
3. Si el incidente es Android, subir hotfix con nuevo `APP_VERSION_CODE`.
