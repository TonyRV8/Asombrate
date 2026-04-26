'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8080);

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
      // Support common Java properties escaping used in local.properties files.
      return rawValue.replace(/\\:/g, ':').replace(/\\\\/g, '\\');
    }
  } catch (_err) {
    return '';
  }

  return '';
}

const ORS_API_KEY = process.env.ORS_API_KEY || readLocalProperty('ORS_API_KEY') || '';
const ORS_BASE_URL = process.env.ORS_BASE_URL || 'https://api.openrouteservice.org';

// Quota baseline (safe budget) for Directions endpoint.
// D = daily quota, M = per-minute quota, k = dynamic factor.
const QUOTA_DAILY_SAFE = Number(process.env.RATE_LIMIT_DAILY_SAFE || 1400);
const QUOTA_PER_MINUTE_SAFE = Number(process.env.RATE_LIMIT_PER_MINUTE_SAFE || 28);
const DYNAMIC_MINUTE_FACTOR_K = Number(process.env.DYNAMIC_MINUTE_FACTOR_K || 1.4);
const dynamicMinuteMinLimitRaw = Number(process.env.DYNAMIC_MINUTE_MIN_LIMIT);
const DYNAMIC_MINUTE_MIN_LIMIT = Number.isFinite(dynamicMinuteMinLimitRaw)
  ? Math.min(QUOTA_PER_MINUTE_SAFE, Math.max(1, Math.floor(dynamicMinuteMinLimitRaw)))
  : Math.min(QUOTA_PER_MINUTE_SAFE, Math.max(2, Math.floor(QUOTA_PER_MINUTE_SAFE * 0.5)));

// Operational thresholds based on accumulated daily consumption.
const THRESHOLD_HIGH_USAGE = Number(process.env.QUOTA_HIGH_USAGE_RATIO || 0.70);
const THRESHOLD_HARDEN = Number(process.env.QUOTA_HARDEN_RATIO || 0.85);
const THRESHOLD_DEGRADED = Number(process.env.QUOTA_DEGRADED_RATIO || 0.95);
const THRESHOLD_BLOCK = Number(process.env.QUOTA_BLOCK_RATIO || 1.00);
const HARDEN_MINUTE_FACTOR = Number(process.env.HARDEN_MINUTE_FACTOR || 0.70);

// Cache tunables.
const CACHE_TTL_MS = Number(process.env.CACHE_TTL_MS || 10 * 60 * 1000);
const CACHE_MAX_DIRECTIONS = Number(process.env.CACHE_MAX_DIRECTIONS || 300);
const CACHE_MAX_GEOCODE = Number(process.env.CACHE_MAX_GEOCODE || 500);
const CACHE_MAX_REVERSE = Number(process.env.CACHE_MAX_REVERSE || 500);

const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 15_000);

// Abuse protection tunables.
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 32 * 1024);
const IP_RATE_LIMIT_PER_MINUTE = Number(process.env.IP_RATE_LIMIT_PER_MINUTE || 120);
const MAX_COORDINATE_PAIRS = Number(process.env.MAX_COORDINATE_PAIRS || 16);
const MAX_GEOCODE_TEXT_LENGTH = Number(process.env.MAX_GEOCODE_TEXT_LENGTH || 200);
const MAX_ROUTE_DISTANCE_KM = Number(process.env.MAX_ROUTE_DISTANCE_KM || 5000);

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
    // Lightweight LRU refresh.
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
}

const directionsCache = new TtlCache(CACHE_TTL_MS, CACHE_MAX_DIRECTIONS);
const geocodeCache = new TtlCache(CACHE_TTL_MS, CACHE_MAX_GEOCODE);
const reverseGeocodeCache = new TtlCache(CACHE_TTL_MS, CACHE_MAX_REVERSE);

const quota = {
  dailyCount: 0,
  minuteCount: 0,
  dailyWindowStartMs: Date.now(),
  minuteBucket: Math.floor(Date.now() / 60_000)
};

const ipCounters = new Map();

const metrics = {
  requestsTotal: 0,
  requestsToOrs: 0,
  cacheHits: 0,
  cacheMisses: 0,
  errors429: 0,
  blockedByQuota: 0,
  blockedByIp: 0,
  degradedCacheServed: 0,
  degradedRejected: 0
};

let requestSequence = 0;
let lastLoggedQuotaStage = 'INIT';

function logEvent(event, fields = {}) {
  const payload = {
    ts: new Date().toISOString(),
    event,
    ...fields
  };
  console.log(JSON.stringify(payload));
}

function metricSnapshot() {
  return {
    requestsTotal: metrics.requestsTotal,
    requestsToOrs: metrics.requestsToOrs,
    cacheHits: metrics.cacheHits,
    cacheMisses: metrics.cacheMisses,
    errors429: metrics.errors429,
    blockedByQuota: metrics.blockedByQuota,
    blockedByIp: metrics.blockedByIp,
    degradedCacheServed: metrics.degradedCacheServed,
    degradedRejected: metrics.degradedRejected
  };
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function currentMinuteBucket(nowMs = Date.now()) {
  return Math.floor(nowMs / 60_000);
}

function refreshQuotaWindows(nowMs = Date.now()) {
  if (nowMs - quota.dailyWindowStartMs >= 24 * 60 * 60 * 1000) {
    quota.dailyCount = 0;
    quota.dailyWindowStartMs = nowMs;
    logEvent('quota.daily_reset', {
      dailyLimit: QUOTA_DAILY_SAFE,
      perMinuteSafe: QUOTA_PER_MINUTE_SAFE
    });
  }

  const bucket = currentMinuteBucket(nowMs);
  if (bucket !== quota.minuteBucket) {
    quota.minuteBucket = bucket;
    quota.minuteCount = 0;
  }
}

function minutesUntilDailyReset(nowMs = Date.now()) {
  const endMs = quota.dailyWindowStartMs + 24 * 60 * 60 * 1000;
  const remainingMs = Math.max(1, endMs - nowMs);
  return Math.max(1, Math.ceil(remainingMs / 60_000));
}

function quotaStageFromRatio(ratio) {
  if (ratio >= THRESHOLD_BLOCK) return 'BLOCK';
  if (ratio >= THRESHOLD_DEGRADED) return 'DEGRADED';
  if (ratio >= THRESHOLD_HARDEN) return 'HARDEN';
  if (ratio >= THRESHOLD_HIGH_USAGE) return 'HIGH_USAGE';
  return 'NORMAL';
}

function usageHeaderFromStage(stage) {
  if (stage === 'BLOCK') return 'BLOCK';
  if (stage === 'DEGRADED') return 'DEGRADED';
  if (stage === 'HIGH_USAGE' || stage === 'HARDEN') return 'HIGH_USAGE';
  return 'NORMAL';
}

function buildQuotaSnapshot(nowMs = Date.now()) {
  refreshQuotaWindows(nowMs);

  const dailyUsed = quota.dailyCount;
  const dailyLimit = Math.max(1, QUOTA_DAILY_SAFE);
  const dailyRatio = dailyUsed / dailyLimit;
  const stage = quotaStageFromRatio(dailyRatio);

  const remainingDaily = Math.max(0, dailyLimit - dailyUsed);
  const remainingMinutes = minutesUntilDailyReset(nowMs);

  // Dynamic minute target (formula from operational plan):
  // T_m = min(M, max(1, floor((R_d / R_m) * k)))
  const baseMinuteLimit = Math.min(
    QUOTA_PER_MINUTE_SAFE,
    Math.max(1, Math.floor((remainingDaily / remainingMinutes) * DYNAMIC_MINUTE_FACTOR_K))
  );

  let minuteLimitDynamic = baseMinuteLimit;
  if (stage === 'NORMAL' || stage === 'HIGH_USAGE') {
    // Evita bloquear casi todo el trafico al inicio del dia por la formula de pacing.
    minuteLimitDynamic = Math.max(baseMinuteLimit, DYNAMIC_MINUTE_MIN_LIMIT);
  }
  if (stage === 'HARDEN') {
    minuteLimitDynamic = Math.max(1, Math.floor(baseMinuteLimit * HARDEN_MINUTE_FACTOR));
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
    minuteLimitSafe: QUOTA_PER_MINUTE_SAFE,
    minuteLimitBase: baseMinuteLimit,
    minuteLimitDynamic,
    stage,
    headerState: usageHeaderFromStage(stage)
  };

  if (snapshot.stage !== lastLoggedQuotaStage) {
    lastLoggedQuotaStage = snapshot.stage;
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

function consumeDirectionsBudget(snapshot) {
  refreshQuotaWindows();

  if (snapshot.stage === 'BLOCK') {
    return { ok: false, reason: 'BLOCK' };
  }
  if (snapshot.stage === 'DEGRADED') {
    return { ok: false, reason: 'DEGRADED' };
  }
  if (quota.dailyCount >= QUOTA_DAILY_SAFE) {
    return { ok: false, reason: 'BLOCK' };
  }
  if (quota.minuteCount >= snapshot.minuteLimitDynamic) {
    return { ok: false, reason: 'MINUTE_DYNAMIC' };
  }

  quota.dailyCount += 1;
  quota.minuteCount += 1;
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

function cleanupIpCounters(nowMs) {
  const cutoffMs = nowMs - 10 * 60_000;
  for (const [ip, entry] of ipCounters.entries()) {
    if (entry.lastSeen < cutoffMs) {
      ipCounters.delete(ip);
    }
  }
}

function checkIpRateLimit(req, nowMs = Date.now()) {
  const ip = extractClientIp(req);
  const bucket = currentMinuteBucket(nowMs);

  let entry = ipCounters.get(ip);
  if (!entry || entry.bucket !== bucket) {
    entry = { bucket, count: 0, lastSeen: nowMs };
  }

  entry.count += 1;
  entry.lastSeen = nowMs;
  ipCounters.set(ip, entry);

  if (ipCounters.size > 1000 && bucket % 5 === 0) {
    cleanupIpCounters(nowMs);
  }

  return entry.count <= IP_RATE_LIMIT_PER_MINUTE;
}

function jsonResponse(res, statusCode, payload, extraHeaders = {}) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    ...extraHeaders
  });
  res.end(body);
}

function respond(res, ctx, statusCode, payload, headers = {}, logFields = {}) {
  jsonResponse(res, statusCode, payload, headers);
  logEvent('request.completed', {
    requestId: ctx.requestId,
    path: ctx.path,
    statusCode,
    durationMs: Date.now() - ctx.startMs,
    ...logFields,
    metrics: metricSnapshot()
  });
}

function errorResponse(res, ctx, statusCode, error, message, headers = {}, logFields = {}) {
  respond(res, ctx, statusCode, { error, message }, headers, {
    error,
    ...logFields
  });
}

async function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';

    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > MAX_BODY_BYTES) {
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

async function fetchJson(url, options) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
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

function validateDirectionsPayload(body) {
  if (!body || typeof body !== 'object') {
    return { ok: false, message: 'Payload de directions invalido.' };
  }

  const coordinates = body.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) {
    return { ok: false, message: 'Se requieren al menos 2 coordenadas.' };
  }
  if (coordinates.length > MAX_COORDINATE_PAIRS) {
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
  if (approxDistanceKm > MAX_ROUTE_DISTANCE_KM) {
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

async function handleDirections(req, res, ctx, body) {
  const validation = validateDirectionsPayload(body);
  if (!validation.ok) {
    const snapshot = buildQuotaSnapshot();
    errorResponse(
      res,
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
  const snapshot = buildQuotaSnapshot();

  if (snapshot.stage === 'BLOCK') {
    metrics.errors429 += 1;
    metrics.blockedByQuota += 1;
    errorResponse(
      res,
      ctx,
      429,
      QUOTA_ERROR_PAYLOAD.error,
      QUOTA_ERROR_PAYLOAD.message,
      buildUsageHeaders(snapshot, { 'X-Usage-State': 'BLOCK' }),
      { reason: 'BLOCK', ...quotaLogFields(snapshot) }
    );
    return;
  }

  const cached = directionsCache.get(key);
  if (cached) {
    metrics.cacheHits += 1;
    if (snapshot.stage === 'DEGRADED') {
      metrics.degradedCacheServed += 1;
    }
    respond(
      res,
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

  metrics.cacheMisses += 1;

  if (snapshot.stage === 'DEGRADED') {
    metrics.degradedRejected += 1;
    errorResponse(
      res,
      ctx,
      503,
      DEGRADED_ERROR_PAYLOAD.error,
      DEGRADED_ERROR_PAYLOAD.message,
      buildUsageHeaders(snapshot, { 'X-Usage-State': 'DEGRADED' }),
      { reason: 'DEGRADED_NO_CACHE', ...quotaLogFields(snapshot) }
    );
    return;
  }

  const consume = consumeDirectionsBudget(snapshot);
  if (!consume.ok) {
    const afterDenied = buildQuotaSnapshot();
    if (consume.reason === 'MINUTE_DYNAMIC') {
      metrics.errors429 += 1;
      errorResponse(
        res,
        ctx,
        429,
        'RATE_LIMITED',
        'Demasiadas solicitudes al servidor. Intenta nuevamente en unos segundos.',
        buildUsageHeaders(afterDenied),
        { reason: 'MINUTE_DYNAMIC', ...quotaLogFields(afterDenied) }
      );
      return;
    }

    metrics.errors429 += 1;
    metrics.blockedByQuota += 1;
    errorResponse(
      res,
      ctx,
      429,
      QUOTA_ERROR_PAYLOAD.error,
      QUOTA_ERROR_PAYLOAD.message,
      buildUsageHeaders(afterDenied, { 'X-Usage-State': 'BLOCK' }),
      { reason: consume.reason, ...quotaLogFields(afterDenied) }
    );
    return;
  }

  const afterConsume = buildQuotaSnapshot();
  if (afterConsume.stage === 'DEGRADED') {
    metrics.degradedRejected += 1;
    errorResponse(
      res,
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
    metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(`${ORS_BASE_URL}/v2/directions/driving-car`, {
      method: 'POST',
      headers: {
        Authorization: ORS_API_KEY,
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({ coordinates: validation.normalizedCoordinates })
    });

    const finalSnapshot = buildQuotaSnapshot();
    if (response.status === 429) {
      metrics.errors429 += 1;
      errorResponse(
        res,
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
        ctx,
        response.status || 502,
        'UPSTREAM_ERROR',
        'Error al consultar el servicio de rutas.',
        buildUsageHeaders(finalSnapshot),
        { reason: 'UPSTREAM_ERROR', upstreamStatus: response.status, ...quotaLogFields(finalSnapshot) }
      );
      return;
    }

    directionsCache.set(key, payload);
    respond(
      res,
      ctx,
      200,
      payload,
      buildUsageHeaders(finalSnapshot, { 'X-Cache': 'MISS' }),
      { cache: 'MISS', ...quotaLogFields(finalSnapshot) }
    );
  } catch (_err) {
    const finalSnapshot = buildQuotaSnapshot();
    errorResponse(
      res,
      ctx,
      503,
      'TEMP_UNAVAILABLE',
      'Servicio temporalmente no disponible.',
      buildUsageHeaders(finalSnapshot),
      { reason: 'FETCH_EXCEPTION', ...quotaLogFields(finalSnapshot) }
    );
  }
}

async function handleGeocode(req, res, ctx, body) {
  const text = normalizeSearchText(body && body.text);
  if (text.length < 2) {
    errorResponse(res, ctx, 400, 'BAD_REQUEST', 'text es obligatorio.');
    return;
  }
  if (text.length > MAX_GEOCODE_TEXT_LENGTH) {
    errorResponse(res, ctx, 400, 'BAD_REQUEST', 'text excede el largo maximo permitido.');
    return;
  }

  const sizeRaw = Number(body && body.size);
  const size = Number.isFinite(sizeRaw) ? clamp(sizeRaw, 1, 10) : 5;
  const key = `${text}|${size}`;

  const cached = geocodeCache.get(key);
  if (cached) {
    metrics.cacheHits += 1;
    respond(res, ctx, 200, cached, { 'X-Cache': 'HIT' }, { cache: 'HIT' });
    return;
  }

  metrics.cacheMisses += 1;

  const url = new URL('/geocode/search', ORS_BASE_URL);
  url.searchParams.set('text', text);
  url.searchParams.set('size', String(size));
  url.searchParams.set('api_key', ORS_API_KEY);

  try {
    metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(url.toString(), { method: 'GET' });
    if (!response.ok || !payload) {
      errorResponse(res, ctx, response.status || 502, 'UPSTREAM_ERROR', 'Error al consultar geocode.', {}, {
        upstreamStatus: response.status
      });
      return;
    }

    geocodeCache.set(key, payload);
    respond(res, ctx, 200, payload, { 'X-Cache': 'MISS' }, { cache: 'MISS' });
  } catch (_err) {
    errorResponse(res, ctx, 503, 'TEMP_UNAVAILABLE', 'Servicio temporalmente no disponible.');
  }
}

async function handleReverseGeocode(req, res, ctx, body) {
  const lat = Number(body && body.lat);
  const lon = Number(body && body.lon);
  if (!isValidLatitude(lat) || !isValidLongitude(lon)) {
    errorResponse(res, ctx, 400, 'BAD_REQUEST', 'lat y lon son obligatorios.');
    return;
  }

  const sizeRaw = Number(body && body.size);
  const size = Number.isFinite(sizeRaw) ? clamp(sizeRaw, 1, 5) : 1;
  const key = `${lat.toFixed(5)},${lon.toFixed(5)}|${size}`;

  const cached = reverseGeocodeCache.get(key);
  if (cached) {
    metrics.cacheHits += 1;
    respond(res, ctx, 200, cached, { 'X-Cache': 'HIT' }, { cache: 'HIT' });
    return;
  }

  metrics.cacheMisses += 1;

  const url = new URL('/geocode/reverse', ORS_BASE_URL);
  url.searchParams.set('point.lat', String(lat));
  url.searchParams.set('point.lon', String(lon));
  url.searchParams.set('size', String(size));
  url.searchParams.set('api_key', ORS_API_KEY);

  try {
    metrics.requestsToOrs += 1;
    const { response, payload } = await fetchJson(url.toString(), { method: 'GET' });
    if (!response.ok || !payload) {
      errorResponse(res, ctx, response.status || 502, 'UPSTREAM_ERROR', 'Error al consultar reverse geocode.', {}, {
        upstreamStatus: response.status
      });
      return;
    }

    reverseGeocodeCache.set(key, payload);
    respond(res, ctx, 200, payload, { 'X-Cache': 'MISS' }, { cache: 'MISS' });
  } catch (_err) {
    errorResponse(res, ctx, 503, 'TEMP_UNAVAILABLE', 'Servicio temporalmente no disponible.');
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    });
    res.end();
    return;
  }

  if (!ORS_API_KEY) {
    jsonResponse(res, 500, {
      error: 'CONFIG_ERROR',
      message: 'ORS_API_KEY no esta configurada en el backend.'
    });
    return;
  }

  if (req.method !== 'POST') {
    jsonResponse(res, 404, { error: 'NOT_FOUND', message: 'Endpoint no disponible.' });
    return;
  }

  metrics.requestsTotal += 1;
  const ctx = {
    requestId: ++requestSequence,
    path: new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`).pathname,
    startMs: Date.now()
  };

  if (!checkIpRateLimit(req, ctx.startMs)) {
    metrics.errors429 += 1;
    metrics.blockedByIp += 1;
    errorResponse(
      res,
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
    body = await readJsonBody(req);
  } catch (err) {
    const message = err.message === 'Payload too large'
      ? 'Payload demasiado grande.'
      : 'JSON invalido.';
    errorResponse(res, ctx, 400, 'BAD_REQUEST', message);
    return;
  }

  if (ctx.path === '/directions') {
    await handleDirections(req, res, ctx, body);
    return;
  }

  if (ctx.path === '/geocode') {
    await handleGeocode(req, res, ctx, body);
    return;
  }

  if (ctx.path === '/reverse-geocode') {
    await handleReverseGeocode(req, res, ctx, body);
    return;
  }

  errorResponse(res, ctx, 404, 'NOT_FOUND', 'Endpoint no disponible.');
});

server.listen(PORT, () => {
  logEvent('server.started', {
    port: PORT,
    orsBaseUrl: ORS_BASE_URL,
    dailySafe: QUOTA_DAILY_SAFE,
    minuteSafe: QUOTA_PER_MINUTE_SAFE,
    dynamicFactorK: DYNAMIC_MINUTE_FACTOR_K,
    dynamicMinuteMinLimit: DYNAMIC_MINUTE_MIN_LIMIT,
    cacheTtlMs: CACHE_TTL_MS,
    maxBodyBytes: MAX_BODY_BYTES,
    ipRateLimitPerMinute: IP_RATE_LIMIT_PER_MINUTE
  });
});
