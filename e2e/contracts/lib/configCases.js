const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { request, waitUntil } = require('./http');
const { stack } = require('./stack');
const { token, bearer, sign } = require('./tokens');
const { parseSamples } = require('./prometheus');
const { startEngine, startFailingEngine, SINGLE_DATABASE_ENV } = require('./containers');
const { PROFILE, openSocket } = require('./traffic');

const P = stack.project;
const service = () => bearer(token({ role: 'service' }));
const EXPOSE_PROMETHEUS = { MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: 'health,info,metrics,prometheus' };
const MULTI_TENANT_ENV = {
  APP_SECURITY_AUTH_HMAC_SECRET: stack.hmacSecret,
  APP_SECURITY_MULTI_TENANT_PROVISIONING_URL: 'http://mock-vault:8080/api',
  APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT: 'contract-pat',
  APP_SECURITY_RLS_POLICY_URL: 'http://mock-vault:8080/api',
};
const withoutKeys = (env, ...keys) => Object.fromEntries(Object.entries(env).filter(([key]) => !keys.includes(key)));
const READY_MANAGEMENT = { readyPort: 8081, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 };
const READY_APP = { readyPort: 10000, readyPath: '/', readyUntil: (response) => response.status > 0 };

const ONE_ROW = 'customer_id=eq.1&select=customer_id';
const rows = (engine, query = 'order=customer_id.asc&select=customer_id') =>
  request(engine.app, `/${P}/api/v1/customer?${query}`, { headers: { ...service(), ...PROFILE } });
const graphqlAs = (engine, query, headers = service()) =>
  request(engine.app, `/${P}/graphql`, { method: 'POST', headers, body: { query } });
const summary = (response) => ({ status: response.status, rows: response.status === 200 ? JSON.parse(response.text).data.length : undefined });

async function metricValue(engine, name) {
  const samples = parseSamples((await request(engine.management, '/actuator/prometheus')).text);
  const sample = samples.find((candidate) => candidate.name === name);
  return sample ? sample.value : null;
}

async function metricNames(engine) {
  return new Set(parseSamples((await request(engine.management, '/actuator/prometheus')).text).map((sample) => sample.name));
}

async function journalBearers(uniqueBearer) {
  const journal = JSON.parse((await request(stack.vault, '/__admin/requests?limit=500')).text);
  return journal.requests.filter((entry) => (entry.request.headers || {}).Authorization === `Bearer ${uniqueBearer}`).map((entry) => entry.request.url);
}

const anyPat = () => `pat-${crypto.randomBytes(6).toString('hex')}`;
const userRead = (engine) => request(engine.app, `/${P}/api/v1/customer?limit=1`, { headers: { ...bearer(token({ role: 'user' })), ...PROFILE } });

function refusal(env, mention) {
  return async () => {
    const result = await startFailingEngine(env);
    return { refused: result.exitCode !== 0, mentions: result.logs.join('\n').includes(mention) };
  };
}

function runtime(env, opts, observe) {
  return async () => {
    const engine = await startEngine(env, opts);
    try {
      return await observe(engine);
    } finally {
      engine.stop();
    }
  };
}

const NO_POLICY_PAT = withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_RLS_POLICY_PAT');

/** Which of the given tokens the engine presented to the control plane when it fetched permissions. */
function patCase(env, tokens, mounts = []) {
  return runtime({ ...NO_POLICY_PAT, ...env }, { ...READY_MANAGEMENT, mounts }, async (engine) => {
    await userRead(engine);
    const used = {};
    for (const [label, value] of Object.entries(tokens)) {
      used[label] = (await journalBearers(value)).some((url) => url.includes('/permissions'));
    }
    return used;
  });
}

const patFile = (content) => {
  const file = path.join(os.tmpdir(), `contract-pat-${crypto.randomBytes(4).toString('hex')}`);
  fs.writeFileSync(file, content, { mode: 0o644 });
  return file;
};

const [multiTenantPat, aliasPat, policyPat, otherPat, literalPat, filePat] = Array.from({ length: 6 }, anyPat);
const PAT_FILE = patFile(filePat);

const CASES = [
  ['POSTGRES_URL, POSTGRES_USER and POSTGRES_PASSWORD select the database', runtime(SINGLE_DATABASE_ENV, READY_APP, async (engine) => summary(await rows(engine, ONE_ROW))), { status: 200, rows: 1 }],
  ['SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD select the database', runtime({
    ...withoutKeys(SINGLE_DATABASE_ENV, 'POSTGRES_URL', 'POSTGRES_USER', 'POSTGRES_PASSWORD'),
    SPRING_DATASOURCE_URL: SINGLE_DATABASE_ENV.POSTGRES_URL, SPRING_DATASOURCE_USERNAME: 'app_user', SPRING_DATASOURCE_PASSWORD: 'password123',
  }, READY_APP, async (engine) => summary(await rows(engine, ONE_ROW))), { status: 200, rows: 1 }],
  ['SPRING_DATASOURCE_PASSWORD wins over POSTGRES_PASSWORD', runtime({ ...SINGLE_DATABASE_ENV, SPRING_DATASOURCE_PASSWORD: 'wrong-password' }, READY_APP, async (engine) => (await rows(engine)).status >= 500 || (await request(engine.management, '/actuator/health')).status === 503), true],
  ['a database URL with no credentials refuses to start', refusal(withoutKeys(SINGLE_DATABASE_ENV, 'POSTGRES_USER', 'POSTGRES_PASSWORD'), 'spring.datasource.username'), { refused: true, mentions: true }],
  ['no database URL and no provisioning URL refuses to start', refusal(withoutKeys(SINGLE_DATABASE_ENV, 'POSTGRES_URL', 'POSTGRES_USER', 'POSTGRES_PASSWORD'), 'datasource'), { refused: true, mentions: true }],
  ['a database URL together with a provisioning URL refuses to start', refusal({ ...SINGLE_DATABASE_ENV, ...MULTI_TENANT_ENV }, 'remove the default datasource'), { refused: true, mentions: true }],
  ['JWT on with no verifier refuses to start', refusal(withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_AUTH_HMAC_SECRET'), 'needs a token verifier'), { refused: true, mentions: true }],
  ['a JWKS URL together with an HMAC secret refuses to start', refusal({ ...SINGLE_DATABASE_ENV, APP_SECURITY_AUTH_JWKS_URL: 'http://mock-vault:8080/jwks' }, 'not both'), { refused: true, mentions: true }],
  ['an HMAC secret under 32 characters refuses to start', refusal({ ...SINGLE_DATABASE_ENV, APP_SECURITY_AUTH_HMAC_SECRET: 'short' }, 'at least 32 characters'), { refused: true, mentions: true }],
  ['an unreachable JWKS URL refuses to start', refusal({ ...withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_AUTH_HMAC_SECRET'), APP_SECURITY_AUTH_JWKS_URL: 'http://mock-vault:8080/no-jwks' }, 'failed to load JWKS'), { refused: true, mentions: true }],
  ['JWT_ENABLED=false without the insecure dev switch refuses to start', refusal({ ...withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_AUTH_HMAC_SECRET'), JWT_ENABLED: 'false' }, 'insecure'), { refused: true, mentions: true }],
  ['JWT_ENABLED=false with APP_SECURITY_INSECURE_DEV_MODE=true serves without a token', runtime({ ...withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_AUTH_HMAC_SECRET'), JWT_ENABLED: 'false', APP_SECURITY_INSECURE_DEV_MODE: 'true' }, READY_APP, async (engine) => ({
    anonymous: (await graphqlAs(engine, '{ __typename }', {})).status, tokenIgnored: (await graphqlAs(engine, '{ __typename }', { Authorization: 'Bearer junk' })).status,
  })), { anonymous: 200, tokenIgnored: 200 }],
  ['an unknown observability level refuses to start', refusal({ ...SINGLE_DATABASE_ENV, EXCALIBASE_OBSERVABILITY_LEVEL: 'bogus' }, 'must be one of full, lite, off'), { refused: true, mentions: true }],
  ['SERVER_PORT moves the public port', runtime({ ...SINGLE_DATABASE_ENV, SERVER_PORT: '10123' }, { readyPort: 10123, readyPath: '/' }, async (engine) => ({
    moved: (await request(engine.app, `/${P}/graphql`, { method: 'POST', headers: service(), body: { query: '{ __typename }' } })).status,
    oldPortClosed: await request(`http://${engine.address}:10000`, '/').then(() => false, (error) => error.code === 'ECONNREFUSED'),
  })), { moved: 200, oldPortClosed: true }],
  ['MANAGEMENT_SERVER_PORT moves the actuator port', runtime({ ...SINGLE_DATABASE_ENV, MANAGEMENT_SERVER_PORT: '8099' }, { readyPort: 8099, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 }, async (engine) => ({
    moved: (await request(engine.management, '/actuator/health')).status,
    oldPortClosed: await request(`http://${engine.address}:8081`, '/actuator/health').then(() => false, (error) => error.code === 'ECONNREFUSED'),
  })), { moved: 200, oldPortClosed: true }],
  ['APP_MAX_ROWS caps the rows of a read', runtime({ ...SINGLE_DATABASE_ENV, APP_MAX_ROWS: '2' }, READY_APP, async (engine) => ({ rest: summary(await rows(engine)), graphql: JSON.parse((await graphqlAs(engine, '{ hanaCustomer { customer_id } }')).text).data.hanaCustomer.length })), { rest: { status: 200, rows: 2 }, graphql: 2 }],
  ['APP_MAX_QUERY_DEPTH limits GraphQL nesting', runtime({ ...SINGLE_DATABASE_ENV, APP_MAX_QUERY_DEPTH: '2' }, READY_APP, async (engine) => JSON.parse((await graphqlAs(engine, '{ hanaCustomer { hanaOrders { hanaCustomer { customer_id } } } }')).text).errors[0].message), 'Query depth 4 exceeds maximum allowed depth of 2'],
  ['APP_RESERVED_SCHEMAS hides a schema from every API', runtime({ ...SINGLE_DATABASE_ENV, APP_RESERVED_SCHEMAS: 'hana' }, READY_APP, async (engine) => ({
    rest: (await rows(engine)).status, graphql: JSON.parse((await graphqlAs(engine, '{ hanaCustomer { customer_id } }')).text).errors[0].message,
  })), { rest: 404, graphql: 'Unknown field(s): hanaCustomer' }],
  // Observed bug: application.yaml documents LOG_FORMAT=plain, but the structured-format setting rejects it and the engine exits.
  ['LOG_FORMAT=plain, documented as the plain console, makes the engine exit at start (observed)', refusal({ ...SINGLE_DATABASE_ENV, LOG_FORMAT: 'plain' }, "Unknown format 'plain'"), { refused: true, mentions: true }],
  ['LOGGING_LEVEL_IO_GITHUB_EXCALIBASE=WARN silences the engine\'s own INFO lines', runtime({ ...SINGLE_DATABASE_ENV, LOGGING_LEVEL_IO_GITHUB_EXCALIBASE: 'WARN' }, READY_APP, async (engine) => engine.logs().filter((line) => line.startsWith('{')).map((line) => JSON.parse(line).log)
    .some((entry) => entry.logger.startsWith('io.github.excalibase') && entry.level === 'INFO')), false],
  ['LOGGING_LEVEL_IO_GITHUB_EXCALIBASE=DEBUG adds DEBUG lines', runtime({ ...SINGLE_DATABASE_ENV, LOGGING_LEVEL_IO_GITHUB_EXCALIBASE: 'DEBUG' }, READY_APP, async (engine) => {
    const session = openSocket(engine.app, service());   // a connection is logged at DEBUG by the engine's own handler
    await new Promise((resolve) => setTimeout(resolve, 1500));
    session.socket.close();
    await new Promise((resolve) => setTimeout(resolve, 500));
    return engine.logs().filter((line) => line.startsWith('{')).map((line) => JSON.parse(line).log.level).includes('DEBUG');
  }), true],
  ['JWT_REQUIRE_AUD=false accepts a token with no audience', runtime({ ...SINGLE_DATABASE_ENV, JWT_REQUIRE_AUD: 'false' }, READY_APP, async (engine) => (await graphqlAs(engine, '{ __typename }', bearer(token({ extra: { aud: undefined } })))).status), 200],
  ['the audience is required by default', runtime(SINGLE_DATABASE_ENV, READY_APP, async (engine) => (await graphqlAs(engine, '{ __typename }', bearer(token({ extra: { aud: undefined } })))).status), 401],
  ['AUTH_AUD_PREFIX changes the expected audience prefix', runtime({ ...SINGLE_DATABASE_ENV, AUTH_AUD_PREFIX: 'custom:' }, READY_APP, async (engine) => ({
    custom: (await graphqlAs(engine, '{ __typename }', bearer(token({ extra: { aud: [`custom:${P}`] } })))).status,
    default: (await graphqlAs(engine, '{ __typename }', bearer(token()))).status,
  })), { custom: 200, default: 401 }],
  ['APP_SECURITY_AUTH_ISSUER requires a matching iss claim', runtime({ ...SINGLE_DATABASE_ENV, APP_SECURITY_AUTH_ISSUER: 'contract-issuer' }, READY_APP, async (engine) => ({
    withoutIssuer: (await graphqlAs(engine, '{ __typename }', bearer(token()))).status,
    withIssuer: (await graphqlAs(engine, '{ __typename }', bearer(token({ extra: { iss: 'contract-issuer' } })))).status,
  })), { withoutIssuer: 401, withIssuer: 200 }],
  // No path is served without a project, so the platform default applies to a project's routes only where no
  // provisioning is configured to say otherwise.
  ['APP_CORS_ALLOWED_ORIGINS sets the origins where no provisioning says otherwise', runtime({ ...withoutKeys(SINGLE_DATABASE_ENV, 'APP_SECURITY_RLS_POLICY_URL', 'APP_SECURITY_RLS_POLICY_PAT'), APP_CORS_ALLOWED_ORIGINS: 'https://allowed.contract.test' }, READY_APP, async (engine) => {
    const preflight = (origin) => request(engine.app, `/${P}/graphql`, { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST' } });
    const allowed = await preflight('https://allowed.contract.test');
    const refused = await preflight('https://other.contract.test');
    return { allowed: [allowed.status, allowed.headers['access-control-allow-origin']], refused: refused.status };
  }), { allowed: [200, 'https://allowed.contract.test'], refused: 403 }],
  ['APP_CORS_PROVISIONING_URL overrides the policy URL as the per-project origin source', runtime({ ...SINGLE_DATABASE_ENV, APP_CORS_PROVISIONING_URL: `http://mock-vault:8080/cors-${P}` }, READY_APP, async (engine) => {
    await request(engine.app, `/${P}/graphql`, { method: 'OPTIONS', headers: { Origin: 'https://x.contract.test', 'Access-Control-Request-Method': 'POST' } });
    const journal = JSON.parse((await request(stack.vault, '/__admin/requests?limit=200')).text);
    return journal.requests.some((entry) => entry.request.url === `/cors-${P}/projects/${P}/info`);
  }), true],
  ['APP_REQUEST_METRICS_ENABLED and _PORT serve the per-project metrics on that port', runtime({ ...SINGLE_DATABASE_ENV, APP_REQUEST_METRICS_ENABLED: 'true', APP_REQUEST_METRICS_PORT: '9555' }, READY_MANAGEMENT, async (engine) => {
    await rows(engine);
    const served = await request(`http://${engine.address}:9555`, '/metrics');
    return { status: served.status, hasProjectSeries: /excalibase_requests_seconds_count\{[^}]*project="e2e-test"/.test(served.text) };
  }), { status: 200, hasProjectSeries: true }],
  ['APP_OVERLOAD_MAX_CONNECTIONS_PER_CPU times the CPUs is the connection bound', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, APP_OVERLOAD_MAX_CONNECTIONS_PER_CPU: '7' }, { ...READY_MANAGEMENT, cpus: 1 }, (engine) => metricValue(engine, 'tomcat_connections_config_max_connections')), 7],
  ['the connection bound defaults to 256 per CPU', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS }, { ...READY_MANAGEMENT, cpus: 1 }, (engine) => metricValue(engine, 'tomcat_connections_config_max_connections')), 256],
  ['APP_OVERLOAD_ENABLED=false leaves the server default bound', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, APP_OVERLOAD_ENABLED: 'false' }, { ...READY_MANAGEMENT, cpus: 1 }, (engine) => metricValue(engine, 'tomcat_connections_config_max_connections')), 8192],
  ['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE does not size the pool: it stays at the Hikari default (observed)', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: '7' }, READY_MANAGEMENT, (engine) => metricValue(engine, 'hikaricp_connections_max')), 10],
  ['spring.datasource.hikari.maximum-pool-size: 50 in application.yaml is not applied either: 10 connections (observed)', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS }, READY_MANAGEMENT, (engine) => metricValue(engine, 'hikaricp_connections_max')), 10],
  ['SERVER_TOMCAT_THREADS_MAX sets the thread ceiling on platform threads', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, SPRING_THREADS_VIRTUAL_ENABLED: 'false', SERVER_TOMCAT_THREADS_MAX: '17' }, READY_MANAGEMENT, (engine) => metricValue(engine, 'tomcat_threads_config_max_threads')), 17],
  ['with virtual threads, the default, there is no thread ceiling to configure (-1)', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, SERVER_TOMCAT_THREADS_MAX: '17' }, READY_MANAGEMENT, (engine) => metricValue(engine, 'tomcat_threads_config_max_threads')), -1],
  ['MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE exposes the prometheus endpoint', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS }, READY_MANAGEMENT, async (engine) => (await request(engine.management, '/actuator/prometheus')).status), 200],
  ['EXCALIBASE_OBSERVABILITY_LEVEL=lite keeps http.server.requests and drops the security series', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, EXCALIBASE_OBSERVABILITY_LEVEL: 'lite' }, READY_MANAGEMENT, async (engine) => {
    await rows(engine);
    const names = await metricNames(engine);
    return { http: names.has('http_server_requests_seconds_count'), security: [...names].some((name) => name.startsWith('spring_security_')) };
  }), { http: true, security: false }],
  ['EXCALIBASE_OBSERVABILITY_LEVEL=off drops http.server.requests too', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS, EXCALIBASE_OBSERVABILITY_LEVEL: 'off' }, READY_MANAGEMENT, async (engine) => {
    await rows(engine);
    const names = await metricNames(engine);
    return { http: names.has('http_server_requests_seconds_count'), security: [...names].some((name) => name.startsWith('spring_security_')) };
  }), { http: false, security: false }],
  ['full observability records both', runtime({ ...SINGLE_DATABASE_ENV, ...EXPOSE_PROMETHEUS }, READY_MANAGEMENT, async (engine) => {
    await rows(engine);
    const names = await metricNames(engine);
    return { http: names.has('http_server_requests_seconds_count'), security: [...names].some((name) => name.startsWith('spring_security_')) };
  }), { http: true, security: !process.env.CONTRACT_VARIANT }],   // Spring Security's own series exist only in its build
  ['SPRING_THREADS_VIRTUAL_ENABLED=false serves requests on platform threads', runtime({ ...SINGLE_DATABASE_ENV, SPRING_THREADS_VIRTUAL_ENABLED: 'false', LOGGING_LEVEL_IO_GITHUB_EXCALIBASE: 'DEBUG' }, READY_APP, async (engine) => {
    await rows(engine, 'first_name=cs.{A}');
    const threads = engine.logs().filter((line) => line.startsWith('{')).map((line) => JSON.parse(line).process.thread.name);
    return { platform: threads.some((name) => /^http-nio-10000-exec-\d+$/.test(name)), virtual: threads.some((name) => name.startsWith('tomcat-handler-')) };
  }), { platform: true, virtual: false }],
  ['virtual threads are the default', runtime(SINGLE_DATABASE_ENV, READY_APP, async (engine) => {
    await rows(engine, 'first_name=cs.{A}');
    const threads = engine.logs().filter((line) => line.startsWith('{')).map((line) => JSON.parse(line).process.thread.name);
    return { platform: threads.some((name) => /^http-nio-10000-exec-\d+$/.test(name)), virtual: threads.some((name) => name.startsWith('tomcat-handler-')) };
  }), { platform: false, virtual: true }],
  ['APP_PROJECT_ID names the only project served', runtime({ ...SINGLE_DATABASE_ENV, APP_PROJECT_ID: 'another-project' }, READY_APP, async (engine) => ({
    named: (await request(engine.app, `/${P}/graphql`, { method: 'POST', headers: service(), body: { query: '{ __typename }' } })).status,
  })), { named: 404 }],
  ['TENANT_DB_SSLMODE defaults to verify-full, which refuses a project database that offers no certificate', runtime(MULTI_TENANT_ENV, READY_APP, async (engine) => (await graphqlAs(engine, '{ __typename }')).status), 503],
  ['TENANT_DB_SSLMODE=disable lets the engine reach a plain project database', runtime({ ...MULTI_TENANT_ENV, TENANT_DB_SSLMODE: 'disable' }, READY_APP, async (engine) => (await graphqlAs(engine, '{ hanaCustomer(limit: 1) { customer_id } }')).status), 200],
  ['PROVISIONING_URL and PROVISIONING_PAT are the multi-tenant source and token', runtime({ APP_SECURITY_AUTH_HMAC_SECRET: stack.hmacSecret, PROVISIONING_URL: 'http://mock-vault:8080/api', PROVISIONING_PAT: 'contract-pat', TENANT_DB_SSLMODE: 'disable', APP_SECURITY_RLS_POLICY_URL: 'http://mock-vault:8080/api' }, READY_APP, async (engine) => (await graphqlAs(engine, '{ hanaCustomer(limit: 1) { customer_id } }')).status), 200],
  ['the policy token falls back to APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT', patCase({ APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT: multiTenantPat }, { multiTenantPat }), { multiTenantPat: true }],
  ['the policy token falls back to the PROVISIONING_PAT alias', patCase({ PROVISIONING_PAT: aliasPat }, { aliasPat }), { aliasPat: true }],
  ['APP_SECURITY_RLS_POLICY_PAT wins over the provisioning token', patCase({ APP_SECURITY_RLS_POLICY_PAT: policyPat, APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT: otherPat }, { policyPat, otherPat }), { policyPat: true, otherPat: false }],
  ['the token file wins over the literal token', patCase({ APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT_FILE: '/tmp/contract-pat', APP_SECURITY_MULTI_TENANT_PROVISIONING_PAT: literalPat }, { filePat, literalPat }, [`${PAT_FILE}:/tmp/contract-pat:ro`]), { filePat: true, literalPat: false }],
];

module.exports = { CASES };
