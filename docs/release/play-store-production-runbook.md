# Play Store Production Runbook - Asombrate

Fecha: 2026-04-25

## 1. Preparación de backend

1. Confirmar deploy activo en `https://asombrate-backend.onrender.com`.
2. Verificar:
   - `GET /healthz` responde `200`.
   - `GET /readyz` responde `200`.
   - `POST /directions`, `POST /geocode` y `POST /reverse-geocode` responden correctamente.
3. Revisar que `ORS_API_KEY` siga solo en backend.

## 2. Configuración local de release

En `local.properties` o variables de entorno:

```properties
BACKEND_BASE_URL_DEBUG=http://10.0.2.2:8081/
BACKEND_BASE_URL_RELEASE=https://asombrate-backend.onrender.com/
APP_VERSION_CODE=1
APP_VERSION_NAME=1.0.0
```

## 3. Validación técnica obligatoria

Windows:

```bash
node --test backend/ors-gateway.test.js
gradlew.bat clean
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
gradlew.bat :app:bundleRelease
```

## 4. Open Testing

1. Subir `app/build/outputs/bundle/release/app-release.aab`.
2. Invitar testers controlados.
3. Ejecutar smoke manual.
4. Monitorear 24-72 horas.

## 5. Producción

1. Incrementar `APP_VERSION_CODE`.
2. Repetir la validación técnica.
3. Publicar con rollout gradual.

## 6. Pendientes externos

- Correo final de contacto para política de privacidad.
- Assets finales de Play Store.
- Confirmación final en Play Console Data Safety.
- Valor final de `APP_VERSION_CODE`.
