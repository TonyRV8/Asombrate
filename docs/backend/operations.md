# Backend Operations - Asombrate

Fecha: 2026-04-25
Backend público actual: https://asombrate-backend.onrender.com

## Propósito

El gateway backend protege la `ORS_API_KEY`, aplica rate limiting, cache TTL, estados operativos y valida payloads antes de llamar a OpenRouteService.

Archivo principal:

- `backend/ors-gateway.js`

## Endpoints

- `GET /healthz`
- `GET /readyz`
- `POST /directions`
- `POST /geocode`
- `POST /reverse-geocode`

## Variables de entorno mínimas

- `ORS_API_KEY`
- `PORT` opcional. Por defecto `8080`.

## Variables recomendadas

- `RATE_LIMIT_DAILY_SAFE=1400`
- `RATE_LIMIT_PER_MINUTE_SAFE=28`
- `DYNAMIC_MINUTE_FACTOR_K=1.4`
- `DYNAMIC_MINUTE_MIN_LIMIT=14`
- `QUOTA_HIGH_USAGE_RATIO=0.70`
- `QUOTA_HARDEN_RATIO=0.85`
- `QUOTA_DEGRADED_RATIO=0.95`
- `QUOTA_BLOCK_RATIO=1.00`
- `CACHE_TTL_MS=600000`
- `IP_RATE_LIMIT_PER_MINUTE=120`
- `REQUEST_TIMEOUT_MS=15000`

## Monitoreo inicial sugerido

- Latencia p95/p99 de `/directions`.
- Conteo de `429`, `503` y `UPSTREAM_ERROR`.
- Evolución de `X-Usage-State`.
- Cache hit ratio.
- Disponibilidad de `GET /readyz`.

## Rollback básico

1. Mantener la última versión estable del servicio en Render.
2. Si aparece degradación fuerte:
   - revertir al último commit estable,
   - redeploy,
   - verificar `GET /readyz`,
   - ejecutar smoke mínimo de directions/geocode/reverse-geocode.
