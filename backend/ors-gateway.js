'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');
const { URL } = require('url');

function readLocalProperty(propertyName) {
  try {
    const localPropertiesPath = path.resolve(__dirname, '..', 'local.properties');
    if (!fs.existsSync(localPropertiesPath)) {
      return '';
    }

    const content = fs.readFileSync(localPropertiesPath, 'utf8');
    const lines = content.split(/\r?\n/);

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) {
        continue;
      }

      const equalIndex = trimmed.indexOf('=');
      if (equalIndex <= 0) {
        continue;
      }

      const key = trimmed.slice(0, equalIndex).trim();
      if (key !== propertyName) {
        continue;
      }

      const rawValue = trimmed.slice(equalIndex + 1).trim();
      return rawValue.replace(/\\:/g, ':').replace(/\\\\/g, '\\');
    }
  } catch (_err) {
    return '';
  }

  return '';
}

function numberFromEnv(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function buildRuntimeConfig(env = process.env) {
  const quotaPerMinuteSafe = numberFromEnv(env.RATE_LIMIT_PER_MINUTE_SAFE, 28);
  const dynamicMinuteMinLimitRaw = Number(env.DYNAMIC_MINUTE_MIN_LIMIT);
  const orsApiKey = Object.prototype.hasOwnProperty.call(env, 'ORS_API_KEY')
    ? String(env.ORS_API_KEY || '')
    : (process.env.ORS_API_KEY || readLocalProperty('ORS_API_KEY') || '');

  return {
    port: numberFromEnv(env.PORT, 8080),
    orsApiKey,
    orsBaseUrl: env.ORS_BASE_URL || 'https://api.openrouteservice.org',
    quotaDailySafe: numberFromEnv(env.RATE_LIMIT_DAILY_SAFE, 1400),
    quotaPerMinuteSafe,
    dynamicMinuteFactorK: numberFromEnv(env.DYNAMIC_MINUTE_FACTOR_K, 1.4),
    dynamicMinuteMinLimit: Number.isFinite(dynamicMinuteMinLimitRaw)
      ? Math.min(quotaPerMinuteSafe, Math.max(1, Math.floor(dynamicMinuteMinLimitRaw)))
      : Math.min(quotaPerMinuteSafe, Math.max(2, Math.floor(quotaPerMinuteSafe * 0.5))),
    thresholdHighUsage: numberFromEnv(env.QUOTA_HIGH_USAGE_RATIO, 0.70),
    thresholdHarden: numberFromEnv(env.QUOTA_HARDEN_RATIO, 0.85),
    thresholdDegraded: numberFromEnv(env.QUOTA_DEGRADED_RATIO, 0.95),
    thresholdBlock: numberFromEnv(env.QUOTA_BLOCK_RATIO, 1.00),
    hardenMinuteFactor: numberFromEnv(env.HARDEN_MINUTE_FACTOR, 0.70),
    cacheTtlMs: numberFromEnv(env.CACHE_TTL_MS, 10 * 60 * 1000),
    cacheMaxDirections: numberFromEnv(env.CACHE_MAX_DIRECTIONS, 300),
    cacheMaxGeocode: numberFromEnv(env.CACHE_MAX_GEOCODE, 500),
    cacheMaxReverse: numberFromEnv(env.CACHE_MAX_REVERSE, 500),
    requestTimeoutMs: numberFromEnv(env.REQUEST_TIMEOUT_MS, 15_000),
    maxBodyBytes: numberFromEnv(env.MAX_BODY_BYTES, 32 * 1024),
    ipRateLimitPerMinute: numberFromEnv(env.IP_RATE_LIMIT_PER_MINUTE, 120),
    maxCoordinatePairs: numberFromEnv(env.MAX_COORDINATE_PAIRS, 16),
    maxGeocodeTextLength: numberFromEnv(env.MAX_GEOCODE_TEXT_LENGTH, 200),
    maxRouteDistanceKm: numberFromEnv(env.MAX_ROUTE_DISTANCE_KM, 5000),
    corsAllowOrigin: env.CORS_ALLOW_ORIGIN || '*'
  };
}

const QUOTA_ERROR_PAYLOAD = {
  error: 'QUOTA_EXCEEDED',
  message: 'Se alcanzo el limite diario de rutas. Intenta nuevamente mas tarde.'
};

const DEGRADED_ERROR_PAYLOAD = {
  error: 'DEGRADED_MODE',
  message: 'Servicio temporalmente no disponible. Intenta nuevamente mas tarde.'
};

class TtlCache {
  constructor(ttlMs, maxEntries = 500) {
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.map = new Map();
  }

  get(key) {
    const entry = this.map.get(key);
    if (!entry) return null;
    if (entry.expiresAt <= Date.now()) {
      this.map.delete(key);
      return null;
    }
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  set(key, value) {
    if (!this.map.has(key) && this.map.size >= this.maxEntries) {
      const oldestKey = this.map.keys().next().value;
      this.map.delete(oldestKey);
    }
    this.map.set(key, { value, expiresAt: Date.now() + this.ttlMs });
  }

  size() {
    return this.map.size;
  }
}

function buildState(config) {
  return {
    config,
    directionsCache: new TtlCache(config.cacheTtlMs, config.cacheMaxDirections),
    geocodeCache: new TtlCache(config.cacheTtlMs, config.cacheMaxGeocode),
    reverseGeocodeCache: new TtlCache(config.cacheTtlMs, config.cacheMaxReverse),
    quota: {
      dailyCount: 0,
      minuteCount: 0,
      dailyWindowStartMs: Date.now(),
      minuteBucket: Math.floor(Date.now() / 60_000)
    },
    ipCounters: new Map(),
    metrics: {
      requestsTotal: 0,
      requestsToOrs: 0,
      cacheHits: 0,
      cacheMisses: 0,
      errors429: 0,
      blockedByQuota: 0,
      blockedByIp: 0,
      degradedCacheServed: 0,
      degradedRejected: 0
    },
    requestSequence: 0,
    lastLoggedQuotaStage: 'INIT',
    startedAtMs: Date.now()
  };
}

function logEvent(event, fields = {}) {
  console.log(JSON.stringify({
    ts: new Date().toISOString(),
    event,
    ...fields
  }));
}

function metricSnapshot(state) {
  return { ...state.metrics };
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function currentMinuteBucket(nowMs = Date.now()) {
  return Math.floor(nowMs / 60_000);
}

function refreshQuotaWindows(state, nowMs = Date.now()) {
  const { quota, config } = state;

  if (nowMs - quota.dailyWindowStartMs >= 24 * 60 * 60 * 1000) {
    quota.dailyCount = 0;
    quota.dailyWindowStartMs = nowMs;
    logEvent('quota.daily_reset', {
      dailyLimit: config.quotaDailySafe,
      perMinuteSafe: config.quotaPerMinuteSafe
    });
  }

  const bucket = currentMinuteBucket(nowMs);
  if (bucket !== quota.minuteBucket) {
    quota.minuteBucket = bucket;
    quota.minuteCount = 0;
  }
}

function minutesUntilDailyReset(state, nowMs = Date.now()) {
  const endMs = state.quota.dailyWindowStartMs + 24 * 60 * 60 * 1000;
  const remainingMs = Math.max(1, endMs - nowMs);
  return Math.max(1, Math.ceil(remainingMs / 60_000));
}

function quotaStageFromRatio(config, ratio) {
  if (ratio >= config.thresholdBlock) return 'BLOCK';
  if (ratio >= config.thresholdDegraded) return 'DEGRADED';
  if (ratio >= config.thresholdHarden) return 'HARDEN';
  if (ratio >= config.thresholdHighUsage) return 'HIGH_USAGE';
  return 'NORMAL';
}

function usageHeaderFromStage(stage) {
  if (stage === 'BLOCK') return 'BLOCK';
  if (stage === 'DEGRADED') return 'DEGRADED';
  if (stage === 'HIGH_USAGE' || stage === 'HARDEN') return 'HIGH_USAGE';
  return 'NORMAL';
}

function buildQuotaSnapshot(state, nowMs = Date.now()) {
  const { quota, config } = state;
  refreshQuotaWindows(state, nowMs);

  const dailyUsed = quota.dailyCount;
  const dailyLimit = Math.max(1, config.quotaDailySafe);
  const dailyRatio = dailyUsed / dailyLimit;
  const stage = quotaStageFromRatio(config, dailyRatio);
  const remainingDaily = Math.max(0, dailyLimit - dailyUsed);
  const remainingMinutes = minutesUntilDailyReset(state, nowMs);

  const baseMinuteLimit = Math.min(
    config.quotaPerMinuteSafe,
    Math.max(1, Math.floor((remainingDaily / remainingMinutes) * config.dynamicMinuteFactorK))
  );

  let minuteLimitDynamic = baseMinuteLimit;
  if (stage === 'NORMAL' || stage === 'HIGH_USAGE') {
    minuteLimitDynamic = Math.max(baseMinuteLimit, config.dynamicMinuteMinLimit);
  }
  if (stage === 'HARDEN') {
    minuteLimitDynamic = Math.max(1, Math.floor(baseMinuteLimit * config.hardenMinuteFactor));
  }
  if (stage === 'DEGRADED') {
    minuteLimitDynamic = 1;
  }
  if (stage === 'BLOCK') {
    minuteLimitDynamic = 0;
  }

  const snapshot = {
    dailyUsed,
    dailyLimit,
    dailyRatio,
    remainingDaily,
    remainingMinutes,
    minuteUsed: quota.minuteCount,
    minuteLimitSafe: config.quotaPerMinuteSafe,
    minuteLimitBase: baseMinuteLimit,
    minuteLimitDynamic,
    stage,
    headerState: usageHeaderFromStage(stage)
  };

  if (snapshot.stage !== state.lastLoggedQuotaStage) {
    state.lastLoggedQuotaStage = snapshot.stage;
    logEvent('quota.state_changed', {
      stage: snapshot.stage,
      usageState: snapshot.headerState,
      dailyUsed: snapshot.dailyUsed,
      dailyLimit: snapshot.dailyLimit,
      minuteLimitDynamic: snapshot.minuteLimitDynamic,
      remainingDaily: snapshot.remainingDaily,
      remainingMinutes: snapshot.remainingMinutes
    });
  }

  return snapshot;
}

function buildUsageHeaders(snapshot, extraHeaders = {}) {
  if (!snapshot) return { ...extraHeaders };
  return {
    'X-Usage-State': snapshot.headerState,
    'X-Usage-Daily-Used': String(snapshot.dailyUsed),
    'X-Usage-Daily-Limit': String(snapshot.dailyLimit),
    'X-Usage-Minute-Used': String(snapshot.minuteUsed),
    'X-Usage-Minute-Limit': String(snapshot.minuteLimitDynamic),
    ...extraHeaders
  };
}

function buildBaseHeaders(state, requestId, extraHeaders = {}) {
  return {
    'Access-Control-Allow-Origin': state.config.corsAllowOrigin,
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'X-Request-Id': String(requestId),
    ...extraHeaders
  };
}

function jsonResponse(res, state, requestId, statusCode, payload, extraHeaders = {}) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, buildBaseHeaders(state, requestId, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    ...extraHeaders
  }));
  res.end(body);
}

function respond(res, state, ctx, statusCode, payload, headers = {}, logFields = {}) {
  jsonResponse(res, state, ctx.requestId, statusCode, payload, headers);
  logEvent('request.completed', {
    requestId: ctx.requestId,
    path: ctx.path,
    statusCode,
    durationMs: Date.now() - ctx.startMs,
    ...logFields,
    metrics: metricSnapshot(state)
  });
}

function errorResponse(res, state, ctx, statusCode, error, message, headers = {}, logFields = {}) {
  respond(res, state, ctx, statusCode, { error, message }, headers, {
    error,
    ...logFields
  });
}

function consumeDirectionsBudget(state, snapshot) {
  refreshQuotaWindows(state);

  if (snapshot.stage === 'BLOCK') {
    return { ok: false, reason: 'BLOCK' };
  }
  if (snapshot.stage === 'DEGRADED') {
    return { ok: false, reason: 'DEGRADED' };
  }
  if (state.quota.dailyCount >= state.config.quotaDailySafe) {
    return { ok: false, reason: 'BLOCK' };
  }
  if (state.quota.minuteCount >= snapshot.minuteLimitDynamic) {
    return { ok: false, reason: 'MINUTE_DYNAMIC' };
  }

  state.quota.dailyCount += 1;
  state.quota.minuteCount += 1;
  return { ok: true };
}

function normalizeIp(ip) {
  if (!ip) return 'unknown';
  if (ip.startsWith('::ffff:')) return ip.slice(7);
  if (ip === '::1') return '127.0.0.1';
  return ip;
}

function extractClientIp(req) {
  const forwarded = req.headers['x-forwarded-for'];
  if (typeof forwarded === 'string' && forwarded.trim() !== '') {
    return normalizeIp(forwarded.split(',')[0].trim());
  }
  return normalizeIp(req.socket && req.socket.remoteAddress ? req.socket.remoteAddress : 'unknown');
}

function cleanupIpCounters(state, nowMs) {
  const cutoffMs = nowMs - 10 * 60_000;
  for (const [ip, entry] of state.ipCounters.entries()) {
    if (entry.lastSeen < cutoffMs) {
      state.ipCounters.delete(ip);
    }
  }
}

function checkIpRateLimit(state, req, nowMs = Date.now()) {
  const ip = extractClientIp(req);
  const bucket = currentMinuteBucket(nowMs);

  let entry = state.ipCounters.get(ip);
  if (!entry || entry.bucket !== bucket) {
    entry = { bucket, count: 0, lastSeen: nowMs };
  }

  entry.count += 1;
  entry.lastSeen = nowMs;
  state.ipCounters.set(ip, entry);

  if (state.ipCounters.size > 1000 && bucket % 5 === 0) {
    cleanupIpCounters(state, nowMs);
  }

  return entry.count <= state.config.ipRateLimitPerMinute;
}

function readJsonBody(req, state) {
  return new Promise((resolve, reject) => {
    let raw = '';

    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > state.config.maxBodyBytes) {
        reject(new Error('Payload too large'));
        req.destroy();
      }
    });

    req.on('end', () => {
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (_err) {
        reject(new Error('Invalid JSON'));
      }
    });

    req.on('error', reject);
  });
}

async function fetchJson(state, url, options) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(state.config.requestTimeoutMs)
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch (_err) {
    payload = null;
  }

  return { response, payload };
}

function isValidLongitude(value) {
  return Number.isFinite(value) && value >= -180 && value <= 180;
}

function isValidLatitude(value) {
  return Number.isFinite(value) && value >= -90 && value <= 90;
}

function normalizeCoordinatePair(pair) {
  if (!Array.isArray(pair) || pair.length < 2) {
    return null;
  }

  const lon = Number(pair[0]);
  const lat = Number(pair[1]);
  if (!isValidLongitude(lon) || !isValidLatitude(lat)) {
    return null;
  }

  return [Number(lon.toFixed(5)), Number(lat.toFixed(5))];
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return 6371 * c;
}

function validateDirectionsPayload(state, body) {
  if (!body || typeof body !== 'object') {
    return { ok: false, message: 'Payload de directions invalido.' };
  }

  const coordinates = body.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) {
    return { ok: false, message: 'Se requieren al menos 2 coordenadas.' };
  }
  if (coordinates.length > state.config.maxCoordinatePairs) {
    return { ok: false, message: 'Demasiados puntos en la ruta solicitada.' };
  }

  const normalized = [];
  for (const pair of coordinates) {
    const point = normalizeCoordinatePair(pair);
    if (!point) {
      return { ok: false, message: 'Coordenadas invalidas en directions.' };
    }

    const prev = normalized[normalized.length - 1];
    if (!prev || prev[0] !== point[0] || prev[1] !== point[1]) {
      normalized.push(point);
    }
  }

  if (normalized.length < 2) {
    return { ok: false, message: 'Origen y destino no pueden ser identicos.' };
  }

  const first = normalized[0];
  const last = normalized[normalized.length - 1];
  const approxDistanceKm = haversineKm(first[1], first[0], last[1], last[0]);
  if (approxDistanceKm > state.config.maxRouteDistanceKm) {
    return { ok: false, message: 'Ruta fuera del rango permitido.' };
  }

  return { ok: true, normalizedCoordinates: normalized };
}

function directionsCacheKey(normalizedCoordinates) {
  return normalizedCoordinates
    .map((pair) => `${pair[0].toFixed(5)},${pair[1].toFixed(5)}`)
    .join('|');
}

function normalizeSearchText(text) {
  return String(text || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ');
}

function quotaLogFields(snapshot) {
  return {
    usageState: snapshot.headerState,
    usageStage: snapshot.stage,
    dailyUsed: snapshot.dailyUsed,
    dailyLimit: snapshot.dailyLimit,
    minuteUsed: snapshot.minuteUsed,
    minuteLimitDynamic: snapshot.minuteLimitDynamic,
    remainingDaily: snapshot.remainingDaily,
    remainingMinutes: snapshot.remainingMinutes
  };
}

async function handleDirections(state, req, res, ctx, body) {
  const validation = validateDirectionsPayload(state, body);
  if (!validation.ok) {
    const snapshot = buildQuotaSnapshot(state);
    errorResponse(
      res,
      state,
      ctx,
      400,
      'BAD_REQUEST',
      validation.message,
      buildUsageHeaders(snapshot),
      quotaLogFields(snapshot)
    );
    return;
  }

  const key = directionsCacheKey(validation.normalizedCoordinates);
  const snapshot = buildQuotaSnapshot(state);

  if (snapshot.stage === 'BLOCK') {
    state.metrics.errors429 += 1;
    state.metrics.blockedByQuota += 1;
    errorResponse(
      res,
      state,
      ctx,
      429,
      QUOTA_ERROR_PAYLOAD.error,
      QUOTA_ERROR_PAYLOAD.message,
      buildUsageHeaders(snapshot, { 'X-Usage-State': 'BLOCK' }),
      { reason: 'BLOCK', ...quotaLogFields(snapshot) }
    );
    return;
  }

  const cached = state.directionsCache.get(key);
  if (cached) {
    state.metrics.cacheHits += 1;
    if (snapshot.stage === 'DEGRADED') {
      state.metrics.degradedCacheServed += 1;
    }
    respond(
      res,
      state,
      ctx,
      200,
      cached,
      buildUsageHeaders(snapshot, {
        'X-Cache': 'HIT',
        'X-Usage-State': snapshot.stage === 'DEGRADED' ? 'DEGRADED' : snapshot.headerState
      }),
      { cache: 'HIT', ...quotaLogFields(snapshot) }
    );
    return;
  }

  state.metrics.cacheMisses += 1;

  if (snapshot.stage === 'DEGRADED') {
    state.metrics.degradedRejected += 1;
    errorResponse(
      res,
      state,
      ctx,
      503,
      DEGRADED_ERROR_PAYLOAD.error,
      DEGRADED_ERROR_PAYLOAD.message,
      buildUsageHeaders(snapshot, { 'X-Usage-State': 'DEGRADED' }),
      { reason: 'DEGRADED_NO_CACHE', ...quotaLogFields(snapshot) }
    );
    return;
  }

  const consume = consumeDirectionsBudget(state, snapshot);
  if (!consume.ok) {
    const afterDenied = buildQuotaSnapshot(state);
    if (consume.reason === 'MINUTE_DYNAMIC') {
      state.metrics.errors429 += 1;
      errorResponse(
        res,
        state,
        ctx,
        429,
        'RATE_LIMITED',
        'Demasiadas solicitudes al servidor. Intenta nuevamente en unos segundos.',
        buildUsageHeaders(afterDenied),
        { reason: 'MINUTE_DYNAMIC', ...quotaLogFields(afterDenied) }
      );
      return;
    }

    state.metrics.errors429 += 1;
    state.metrics.blockedByQuota += 1;
    errorResponse(
      res,
      state,
      ctx,
      429,
      QUOTA_ERROR_PAYLOAD.error,
      QUOTA_ERROR_PAYLOAD.message,
      buildUsageHeaders(afterDenied, { 'X-Usage-State': 'BLOCK' }),
      { reason: consume.reason, ...quotaLogFields(afterDenied) }
    );
    return;
  }

  const afterConsume = buildQuotaSnapshot(state);
  if (afterConsume.stage === 'DEGRADED') {
    state.metrics.degradedRejected += 1;
    errorResponse(
      res,
      state,
      ctx,
      503,
      DEGRADED_ERROR_PAYLOAD.error,
      DEGRADED_ERROR_PAYLOAD.message,
      buildUsageHeaders(afterConsume, { 'X-Usage-State': 'DEGRADED' }),
      { reason: 'DEGRADED_AFTER_CONSUME', ...quotaLogFields(afterConsume) }
    );
    return;
  }

  try {
    state.metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(
      state,
      `${state.config.orsBaseUrl}/v2/directions/driving-car`,
      {
        method: 'POST',
        headers: {
          Authorization: state.config.orsApiKey,
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify({ coordinates: validation.normalizedCoordinates })
      }
    );

    const finalSnapshot = buildQuotaSnapshot(state);
    if (response.status === 429) {
      state.metrics.errors429 += 1;
      errorResponse(
        res,
        state,
        ctx,
        429,
        QUOTA_ERROR_PAYLOAD.error,
        QUOTA_ERROR_PAYLOAD.message,
        buildUsageHeaders(finalSnapshot, { 'X-Usage-State': 'BLOCK' }),
        { reason: 'UPSTREAM_429', ...quotaLogFields(finalSnapshot) }
      );
      return;
    }

    if (!response.ok || !payload) {
      errorResponse(
        res,
        state,
        ctx,
        response.status || 502,
        'UPSTREAM_ERROR',
        'Error al consultar el servicio de rutas.',
        buildUsageHeaders(finalSnapshot),
        { reason: 'UPSTREAM_ERROR', upstreamStatus: response.status, ...quotaLogFields(finalSnapshot) }
      );
      return;
    }

    state.directionsCache.set(key, payload);
    respond(
      res,
      state,
      ctx,
      200,
      payload,
      buildUsageHeaders(finalSnapshot, { 'X-Cache': 'MISS' }),
      { cache: 'MISS', ...quotaLogFields(finalSnapshot) }
    );
  } catch (_err) {
    const finalSnapshot = buildQuotaSnapshot(state);
    errorResponse(
      res,
      state,
      ctx,
      503,
      'TEMP_UNAVAILABLE',
      'Servicio temporalmente no disponible.',
      buildUsageHeaders(finalSnapshot),
      { reason: 'FETCH_EXCEPTION', ...quotaLogFields(finalSnapshot) }
    );
  }
}

async function handleGeocode(state, res, ctx, body) {
  const text = normalizeSearchText(body && body.text);
  if (text.length < 2) {
    errorResponse(res, state, ctx, 400, 'BAD_REQUEST', 'text es obligatorio.');
    return;
  }
  if (text.length > state.config.maxGeocodeTextLength) {
    errorResponse(res, state, ctx, 400, 'BAD_REQUEST', 'text excede el largo maximo permitido.');
    return;
  }

  const sizeRaw = Number(body && body.size);
  const size = Number.isFinite(sizeRaw) ? clamp(sizeRaw, 1, 10) : 5;
  const key = `${text}|${size}`;

  const cached = state.geocodeCache.get(key);
  if (cached) {
    state.metrics.cacheHits += 1;
    respond(res, state, ctx, 200, cached, { 'X-Cache': 'HIT' }, { cache: 'HIT' });
    return;
  }

  state.metrics.cacheMisses += 1;

  const url = new URL('/geocode/search', state.config.orsBaseUrl);
  url.searchParams.set('text', text);
  url.searchParams.set('size', String(size));
  url.searchParams.set('api_key', state.config.orsApiKey);

  try {
    state.metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(state, url.toString(), { method: 'GET' });
    if (!response.ok || !payload) {
      errorResponse(
        res,
        state,
        ctx,
        response.status || 502,
        'UPSTREAM_ERROR',
        'Error al consultar geocode.',
        {},
        { upstreamStatus: response.status }
      );
      return;
    }

    state.geocodeCache.set(key, payload);
    respond(res, state, ctx, 200, payload, { 'X-Cache': 'MISS' }, { cache: 'MISS' });
  } catch (_err) {
    errorResponse(res, state, ctx, 503, 'TEMP_UNAVAILABLE', 'Servicio temporalmente no disponible.');
  }
}

async function handleReverseGeocode(state, res, ctx, body) {
  const lat = Number(body && body.lat);
  const lon = Number(body && body.lon);
  if (!isValidLatitude(lat) || !isValidLongitude(lon)) {
    errorResponse(res, state, ctx, 400, 'BAD_REQUEST', 'lat y lon son obligatorios.');
    return;
  }

  const sizeRaw = Number(body && body.size);
  const size = Number.isFinite(sizeRaw) ? clamp(sizeRaw, 1, 5) : 1;
  const key = `${lat.toFixed(5)},${lon.toFixed(5)}|${size}`;

  const cached = state.reverseGeocodeCache.get(key);
  if (cached) {
    state.metrics.cacheHits += 1;
    respond(res, state, ctx, 200, cached, { 'X-Cache': 'HIT' }, { cache: 'HIT' });
    return;
  }

  state.metrics.cacheMisses += 1;

  const url = new URL('/geocode/reverse', state.config.orsBaseUrl);
  url.searchParams.set('point.lat', String(lat));
  url.searchParams.set('point.lon', String(lon));
  url.searchParams.set('size', String(size));
  url.searchParams.set('api_key', state.config.orsApiKey);

  try {
    state.metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(state, url.toString(), { method: 'GET' });
    if (!response.ok || !payload) {
      errorResponse(
        res,
        state,
        ctx,
        response.status || 502,
        'UPSTREAM_ERROR',
        'Error al consultar reverse geocode.',
        {},
        { upstreamStatus: response.status }
      );
      return;
    }

    state.reverseGeocodeCache.set(key, payload);
    respond(res, state, ctx, 200, payload, { 'X-Cache': 'MISS' }, { cache: 'MISS' });
  } catch (_err) {
    errorResponse(res, state, ctx, 503, 'TEMP_UNAVAILABLE', 'Servicio temporalmente no disponible.');
  }
}

function buildHealthPayload(state) {
  return {
    status: state.config.orsApiKey ? 'ok' : 'degraded',
    service: 'asombrate-ors-gateway',
    uptimeSeconds: Math.floor((Date.now() - state.startedAtMs) / 1000),
    orsConfigured: Boolean(state.config.orsApiKey),
    quota: buildQuotaSnapshot(state),
    caches: {
      directionsEntries: state.directionsCache.size(),
      geocodeEntries: state.geocodeCache.size(),
      reverseGeocodeEntries: state.reverseGeocodeCache.size()
    },
    metrics: metricSnapshot(state)
  };
}

function handleHealth(state, res, ctx) {
  respond(res, state, ctx, 200, buildHealthPayload(state));
}

function handleReady(state, res, ctx) {
  if (!state.config.orsApiKey) {
    errorResponse(
      res,
      state,
      ctx,
      503,
      'CONFIG_ERROR',
      'ORS_API_KEY no esta configurada en el backend.'
    );
    return;
  }
  respond(res, state, ctx, 200, {
    status: 'ready',
    service: 'asombrate-ors-gateway'
  });
}

function createServer(options = {}) {
  const config = options.config || buildRuntimeConfig(options.env);
  const state = options.state || buildState(config);

  const server = http.createServer(async (req, res) => {
    const ctx = {
      requestId: ++state.requestSequence,
      path: new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`).pathname,
      startMs: Date.now()
    };

    if (req.method === 'OPTIONS') {
      res.writeHead(204, buildBaseHeaders(state, ctx.requestId));
      res.end();
      return;
    }

    if (ctx.path === '/healthz' && req.method === 'GET') {
      handleHealth(state, res, ctx);
      return;
    }

    if (ctx.path === '/readyz' && req.method === 'GET') {
      handleReady(state, res, ctx);
      return;
    }

    if (!state.config.orsApiKey) {
      errorResponse(
        res,
        state,
        ctx,
        503,
        'CONFIG_ERROR',
        'ORS_API_KEY no esta configurada en el backend.'
      );
      return;
    }

    if (req.method !== 'POST') {
      errorResponse(res, state, ctx, 404, 'NOT_FOUND', 'Endpoint no disponible.');
      return;
    }

    const contentType = String(req.headers['content-type'] || '');
    if (!contentType.toLowerCase().startsWith('application/json')) {
      errorResponse(
        res,
        state,
        ctx,
        415,
        'UNSUPPORTED_MEDIA_TYPE',
        'Content-Type debe ser application/json.'
      );
      return;
    }

    state.metrics.requestsTotal += 1;

    if (!checkIpRateLimit(state, req, ctx.startMs)) {
      state.metrics.errors429 += 1;
      state.metrics.blockedByIp += 1;
      errorResponse(
        res,
        state,
        ctx,
        429,
        'TOO_MANY_REQUESTS',
        'Demasiadas solicitudes. Intenta nuevamente en unos segundos.',
        { 'X-Usage-State': 'NORMAL' },
        { reason: 'IP_RATE_LIMITED' }
      );
      return;
    }

    let body;
    try {
      body = await readJsonBody(req, state);
    } catch (err) {
      const message = err.message === 'Payload too large'
        ? 'Payload demasiado grande.'
        : 'JSON invalido.';
      errorResponse(res, state, ctx, 400, 'BAD_REQUEST', message);
      return;
    }

    if (ctx.path === '/directions') {
      await handleDirections(state, req, res, ctx, body);
      return;
    }

    if (ctx.path === '/geocode') {
      await handleGeocode(state, res, ctx, body);
      return;
    }

    if (ctx.path === '/reverse-geocode') {
      await handleReverseGeocode(state, res, ctx, body);
      return;
    }

    errorResponse(res, state, ctx, 404, 'NOT_FOUND', 'Endpoint no disponible.');
  });

  server.asombrateState = state;
  return server;
}

function startServer(options = {}) {
  const config = options.config || buildRuntimeConfig(options.env);
  const server = createServer({ config, state: buildState(config) });
  server.listen(config.port, () => {
    logEvent('server.started', {
      port: config.port,
      orsBaseUrl: config.orsBaseUrl,
      dailySafe: config.quotaDailySafe,
      minuteSafe: config.quotaPerMinuteSafe,
      dynamicFactorK: config.dynamicMinuteFactorK,
      dynamicMinuteMinLimit: config.dynamicMinuteMinLimit,
      cacheTtlMs: config.cacheTtlMs,
      maxBodyBytes: config.maxBodyBytes,
      ipRateLimitPerMinute: config.ipRateLimitPerMinute
    });
  });
  return server;
}

if (require.main === module) {
  startServer();
}

module.exports = {
  QUOTA_ERROR_PAYLOAD,
  DEGRADED_ERROR_PAYLOAD,
  TtlCache,
  buildRuntimeConfig,
  buildState,
  buildQuotaSnapshot,
  createServer,
  directionsCacheKey,
  normalizeCoordinatePair,
  normalizeSearchText,
  startServer,
  validateDirectionsPayload
};
