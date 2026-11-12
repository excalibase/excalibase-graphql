const KEPT_HEADERS = [
  'content-type', 'allow', 'content-range', 'www-authenticate', 'x-auth-reason',
  'access-control-allow-origin', 'access-control-allow-methods', 'access-control-allow-headers',
  'access-control-allow-credentials', 'access-control-max-age', 'access-control-expose-headers',
  'cache-control', 'x-content-type-options', 'x-frame-options', 'x-xss-protection', 'vary',
];

const ISO_TIMESTAMP = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[.\d]*(Z|[+-]\d{2}:\d{2})/g;

function pickHeaders(headers) {
  return Object.fromEntries(KEPT_HEADERS.filter((name) => headers[name] !== undefined).map((name) => [name, headers[name]]));
}

function describeBody(text, contentType = '') {
  if (text === '') return '';
  if (contentType.includes('html')) return `<html: ${(text.match(/<title>(.*?)<\/title>/) || [])[1]}>`;
  if (contentType.includes('openapi')) {
    const spec = JSON.parse(text);
    return { openapi: spec.openapi, info: spec.info, servers: spec.servers, paths: Object.keys(spec.paths).sort() };
  }
  if (contentType.includes('json')) return JSON.parse(text.replace(ISO_TIMESTAMP, '<timestamp>'));
  return text;
}

/** What a client sees, minus values that change per run. */
function capture(response) {
  const contentType = response.headers['content-type'] || '';
  return { status: response.status, headers: pickHeaders(response.headers), body: describeBody(response.text, contentType) };
}

module.exports = { capture, pickHeaders };
