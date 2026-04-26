'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');

const { createServer } = require('./ors-gateway');

async function startTestServer(envOverrides = {}) {
  const server = createServer({
    env: {
      ORS_API_KEY: 'test-key',
      ...envOverrides
    }
  });
  server.listen(0);
  await once(server, 'listening');
  const port = server.address().port;
  return { server, baseUrl: `http://127.0.0.1:${port}` };
}

async function request(baseUrl, path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options);
  const payload = await response.json().catch(() => null);
  return { response, payload };
}

test('GET /healthz exposes operational state without leaking secrets', async () => {
  const { server, baseUrl } = await startTestServer();

  try {
    const { response, payload } = await request(baseUrl, '/healthz');
    assert.equal(response.status, 200);
    assert.equal(payload.service, 'asombrate-ors-gateway');
    assert.equal(payload.orsConfigured, true);
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(response.headers.get('x-frame-options'), 'DENY');
    assert.ok(!JSON.stringify(payload).includes('test-key'));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('GET / returns 200 with endpoint hints', async () => {
  const { server, baseUrl } = await startTestServer();

  try {
    const { response, payload } = await request(baseUrl, '/');
    assert.equal(response.status, 200);
    assert.equal(payload.service, 'asombrate-ors-gateway');
    assert.equal(payload.endpoints.health, '/healthz');
    assert.equal(payload.endpoints.ready, '/readyz');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('GET /readyz returns 503 when ORS_API_KEY is missing', async () => {
  const server = createServer({ env: { ORS_API_KEY: '' } });
  server.listen(0);
  await once(server, 'listening');
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    const { response, payload } = await request(baseUrl, '/readyz');
    assert.equal(response.status, 503);
    assert.equal(payload.error, 'CONFIG_ERROR');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('POST endpoints require application/json', async () => {
  const { server, baseUrl } = await startTestServer();

  try {
    const { response, payload } = await request(baseUrl, '/directions', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: 'hello'
    });
    assert.equal(response.status, 415);
    assert.equal(payload.error, 'UNSUPPORTED_MEDIA_TYPE');
    assert.ok(response.headers.get('x-request-id'));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('POST /directions validates route payload before upstream calls', async () => {
  const { server, baseUrl } = await startTestServer();

  try {
    const { response, payload } = await request(baseUrl, '/directions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ coordinates: [[-99.1, 19.4]] })
    });
    assert.equal(response.status, 400);
    assert.equal(payload.error, 'BAD_REQUEST');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
