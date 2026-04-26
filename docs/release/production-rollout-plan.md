# Plan de salida a produccion (Asombrate)

Este plan asume que el backend ORS gateway sera desplegado por separado en Internet (HTTPS).

## 1) Preparacion de backend publico

1. Desplegar el gateway backend desde [backend/ors-gateway.js](../../backend/ors-gateway.js).
2. Configurar variables de entorno en el proveedor:
   - ORS_API_KEY
   - RATE_LIMIT_DAILY_SAFE
   - RATE_LIMIT_PER_MINUTE_SAFE
   - DYNAMIC_MINUTE_FACTOR_K
   - DYNAMIC_MINUTE_MIN_LIMIT
   - QUOTA_HIGH_USAGE_RATIO
   - QUOTA_HARDEN_RATIO
   - QUOTA_DEGRADED_RATIO
   - QUOTA_BLOCK_RATIO
3. Confirmar endpoint vivo por HTTPS (ejemplo: https://tu-backend.com/directions).
4. Verificar headers operativos (X-Usage-State, X-Usage-Minute-Limit).

## 2) Configuracion Android por entorno

1. Crear/editar local.properties local (no se versiona):
   - BACKEND_BASE_URL_DEBUG=http://10.0.2.2:8081/
   - BACKEND_BASE_URL_RELEASE=https://tu-backend.com/
2. Debug usa URL local automaticamente.
3. Release usa URL HTTPS de produccion automaticamente.
4. Si BACKEND_BASE_URL_RELEASE no es HTTPS real, el build release falla en Gradle.

## 3) Validacion de seguridad de red

1. Debug permite HTTP local via network security config.
2. Release fuerza usesCleartextTraffic=false.
3. Confirmar que la app release funciona solo con HTTPS.

## 4) Smoke de release antes de Play Store

1. Generar release:
   - gradlew.bat :app:assembleRelease
2. Instalar APK/AAB release en dispositivo real.
3. Probar flujo completo:
   - geocode origen
   - geocode destino
   - directions
   - recomendacion final
4. Verificar manejo UX de estados:
   - NORMAL
   - HIGH_USAGE
   - DEGRADED
   - BLOCK
5. Confirmar que no se requiere backend local ni adb reverse.

## 5) Checklist final de publicacion

1. ORS_API_KEY solo en backend (no en cliente de produccion).
2. BACKEND_BASE_URL_RELEASE apuntando a dominio HTTPS estable.
3. Certificado TLS valido.
4. Politica de privacidad y Data Safety alineadas con arquitectura backend.
5. Monitoreo basico backend habilitado (errores, 429, latencias).

## 6) Post-lanzamiento

1. Monitorear ratio de 429 y 5xx.
2. Ajustar DYNAMIC_MINUTE_MIN_LIMIT y umbrales segun trafico real.
3. Mantener cache/rate policy con foco en disponibilidad y proteccion de cuota ORS.
