# Open Testing Release Checklist - Asombrate

Fecha: 2026-04-25

## Backend

- [ ] `GET /healthz` responde `200`.
- [ ] `GET /readyz` responde `200`.
- [ ] `https://asombrate-backend.onrender.com` sigue siendo el backend release oficial.
- [ ] `ORS_API_KEY` solo existe en backend.

## Android release

- [ ] `BACKEND_BASE_URL_RELEASE` apunta a `https://asombrate-backend.onrender.com/`.
- [ ] `APP_VERSION_CODE` incrementado para la nueva subida.
- [ ] `gradlew.bat :app:testDebugUnitTest` pasa.
- [ ] `gradlew.bat :app:assembleDebug` pasa.
- [ ] `gradlew.bat :app:bundleRelease` pasa.

## Seguridad y privacidad

- [ ] Release usa HTTPS exclusivamente.
- [ ] Release no permite cleartext.
- [ ] Backup y data extraction siguen deshabilitados.
- [ ] No hay secretos en `app/build`.

## Smoke manual

- [ ] Flujo feliz origen/destino/ruta/recomendación.
- [ ] Ubicación actual.
- [ ] Error de red visible y entendible en geocode/reverse geocode.
- [ ] Estados `HIGH_USAGE`, `DEGRADED`, `BLOCK` validados.

## Play Console

- [ ] Data Safety copiada desde `docs/play-store/play-console-data-safety-draft.md`.
- [ ] Política de privacidad final publicada con correo real.
- [ ] Screenshots y listing actualizados.

## Go / No-Go

- [ ] GO.
- [ ] NO-GO si falla cualquiera de los bloques anteriores.
