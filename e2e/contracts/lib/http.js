const http = require('http');

/** Sends the path exactly as given: no encoding, no header rewriting. */
function request(base, path, { method = 'GET', headers = {}, body } = {}) {
  const url = new URL(base);
  const payload = body === undefined ? undefined : (typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
  const finalHeaders = { ...headers };
  if (payload !== undefined && !Object.keys(finalHeaders).some((name) => name.toLowerCase() === 'content-type')) {
    finalHeaders['Content-Type'] = 'application/json';
  }
  return new Promise((resolve, reject) => {
    const req = http.request({ host: url.hostname, port: url.port, path, method, headers: finalHeaders, agent: false }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.setTimeout(30000, () => req.destroy(new Error(`timeout ${method} ${path}`)));
    req.on('error', reject);
    if (payload !== undefined) req.write(payload);
    req.end();
  });
}

const json = (response) => JSON.parse(response.text);

async function waitUntil(check, { timeoutMs = 60000, intervalMs = 500, label = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      const value = await check();
      if (value) return value;
    } catch (error) {
      last = error;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`timed out waiting for ${label}${last ? `: ${last.message}` : ''}`);
}

module.exports = { request, json, waitUntil };
