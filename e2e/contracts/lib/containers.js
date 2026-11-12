const { execFileSync } = require('child_process');
const crypto = require('crypto');
const { stack } = require('./stack');
const { waitUntil, request } = require('./http');

const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

const SINGLE_DATABASE_ENV = {
  POSTGRES_URL: 'jdbc:postgresql://postgres:5432/hana',
  POSTGRES_USER: 'app_user',
  POSTGRES_PASSWORD: 'password123',
  APP_PROJECT_ID: stack.project,
  APP_SECURITY_AUTH_HMAC_SECRET: stack.hmacSecret,
  APP_SECURITY_RLS_POLICY_URL: 'http://mock-vault:8080/api',
  APP_SECURITY_RLS_POLICY_PAT: 'contract-pat',
};

const created = new Set();

function networkAddress(name) {
  const format = `{{(index .NetworkSettings.Networks "${stack.network}").IPAddress}}`;
  return docker('inspect', '-f', format, name);
}

/** Starts an engine container on the contract network; resolves once `readyPath` answers on `readyPort`. */
async function startEngine(env, { cpus, network = stack.network, readyPort = 10000, readyPath = '/', readyUntil = (response) => response.status > 0 } = {}) {
  const name = `contract-engine-${crypto.randomBytes(4).toString('hex')}`;
  const args = ['run', '-d', '--name', name, '--network', network];
  if (cpus) args.push('--cpus', String(cpus));
  Object.entries(env).forEach(([key, value]) => args.push('-e', `${key}=${value}`));
  docker(...args, stack.image);
  created.add(name);
  const address = networkAddress(name);
  const engine = {
    name, address,
    app: `http://${address}:${env.SERVER_PORT || 10000}`,
    management: `http://${address}:${env.MANAGEMENT_SERVER_PORT || 8081}`,
    logs: () => docker('logs', name).split('\n').filter(Boolean),
    stop: () => removeContainer(name),
  };
  await waitUntil(async () => readyUntil(await request(`http://${address}:${readyPort}`, readyPath)), { timeoutMs: 90000, label: `${name} ready` })
    .catch((error) => { throw new Error(`${error.message}\n${engine.logs().slice(-5).join('\n')}`); });
  return engine;
}

/** Starts an engine that is expected to refuse to start; returns its exit code and log lines. */
async function startFailingEngine(env) {
  const name = `contract-engine-${crypto.randomBytes(4).toString('hex')}`;
  const args = ['run', '-d', '--name', name, '--network', stack.network];
  Object.entries(env).forEach(([key, value]) => args.push('-e', `${key}=${value}`));
  docker(...args, stack.image);
  created.add(name);
  await waitUntil(() => docker('inspect', '-f', '{{.State.Running}}', name) === 'false', { timeoutMs: 90000, label: `${name} exit` });
  return { exitCode: Number(docker('inspect', '-f', '{{.State.ExitCode}}', name)), logs: docker('logs', name).split('\n').filter(Boolean) };
}

function removeContainer(name) {
  try {
    docker('rm', '-f', name);
  } catch (error) {
    // already gone
  }
  created.delete(name);
}

const removeAll = () => [...created].forEach(removeContainer);

module.exports = { docker, startEngine, startFailingEngine, removeContainer, removeAll, SINGLE_DATABASE_ENV };
