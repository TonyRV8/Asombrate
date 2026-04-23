# Smoke Checklist Backend-Android (Asombrate)

## 1) Configuracion Android local

Ejemplo de local.properties (raiz del proyecto):

BACKEND_BASE_URL=http://10.0.2.2:8080/

Notas:

- En emulador Android, 10.0.2.2 apunta a localhost de tu maquina.
- En dispositivo fisico, usa la IP LAN de tu maquina (ejemplo: http://192.168.1.20:8080/).

## 2) Variables de entorno backend

Requerida:

- ORS_API_KEY=<tu_key_real>

Recomendadas:

- PORT=8080
- RATE_LIMIT_DAILY_SAFE=1400
- RATE_LIMIT_PER_MINUTE_SAFE=28
- DYNAMIC_MINUTE_FACTOR_K=1.4
- CACHE_TTL_MS=600000

Para pruebas forzadas de estados (opcional):

- QUOTA_HIGH_USAGE_RATIO=0.70
- QUOTA_DEGRADED_RATIO=0.95
- QUOTA_BLOCK_RATIO=1.00
- QUOTA_HARDEN_RATIO=0.85

## 3) Build minima antes de smoke

Windows:

- gradlew.bat :app:assembleDebug

## 4) Escenarios de smoke manual

1. Flujo normal

- Condicion: backend con umbrales por defecto.
- Accion: calcular una ruta valida nueva.
- Esperado:
  - Respuesta 200.
  - Header X-Usage-State=NORMAL o HIGH_USAGE segun consumo.
  - App sin errores tecnicos crudos.

2. High usage preventivo

- Condicion: forzar rapido, por ejemplo QUOTA_HIGH_USAGE_RATIO=0.0 temporalmente.
- Accion: calcular ruta.
- Esperado:
  - 200 con X-Usage-State=HIGH_USAGE.
  - Banner preventivo no bloqueante en app.

3. Degraded con cache

- Condicion:
  - Sembrar cache primero con una ruta exitosa.
  - Reiniciar backend con QUOTA_DEGRADED_RATIO=0.0 y QUOTA_BLOCK_RATIO=1.0.
- Accion: recalcular exactamente la misma ruta.
- Esperado:
  - 200 desde cache.
  - X-Usage-State=DEGRADED.
  - App muestra aviso de modo degradado preventivo.

4. Block / cuota agotada

- Condicion: forzar rapido con QUOTA_BLOCK_RATIO=0.0 o presupuesto diario muy bajo.
- Accion: calcular ruta sin cache (nueva combinacion).
- Esperado:
  - 429 con X-Usage-State=BLOCK.
  - Mensaje amigable de cuota agotada.

5. Timeout / temporalmente no disponible

- Condicion: simular upstream caido (por ejemplo ORS_BASE_URL invalido temporalmente).
- Accion: calcular ruta.
- Esperado:
  - Error controlado de indisponibilidad temporal.
  - App muestra mensaje amigable, sin stacktrace ni texto tecnico crudo.

## 5) Criterio de salida

- Contrato de estado backend-Android consistente: NORMAL, HIGH_USAGE, DEGRADED, BLOCK.
- Fallback de ultima ruta valida sigue funcionando.
- Sin regresion del flujo origen/destino/ruta/recomendacion.
