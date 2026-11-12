const WebSocket = require('ws');
const { token, bearer } = require('./tokens');
const { request, waitUntil } = require('./http');
const { stack } = require('./stack');

const PROFILE = { 'Accept-Profile': 'hana', 'Content-Profile': 'hana' };

function graphql(base, query, headers) {
  return request(base, `/${stack.project}/graphql`, { method: 'POST', headers, body: { query } });
}

function openSocket(base, headers = {}, init = {}) {
  const url = `${base.replace('http', 'ws')}/${stack.project}/graphql`;
  const socket = new WebSocket(url, 'graphql-transport-ws', { headers });
  const frames = [];
  const closed = new Promise((resolve) => socket.on('close', (code, reason) => resolve({ code, reason: reason.toString() })));
  socket.on('open', () => socket.send(JSON.stringify({ type: 'connection_init', payload: init })));
  socket.on('message', (raw) => frames.push(JSON.parse(raw.toString())));
  socket.on('error', () => {});
  return { socket, frames, closed };
}

/**
 * One REST read, GraphQL query, mutation, error and WebSocket subscribe against an engine. With `change`, the
 * subscriber is anonymous (the watcher's events carry no tenant) and a row is inserted so a message is delivered.
 */
async function sendKnownMix(base, headers = bearer(token({ role: 'service' })), { change = false } = {}) {
  await request(base, `/${stack.project}/api/v1/customer?limit=1`, { headers: { ...headers, ...PROFILE } });
  await graphql(base, '{ hanaCustomer(limit: 1) { customer_id } }', headers);
  await graphql(base, 'mutation { createHanaCustomer(input: { first_name: "Mix", last_name: "Row" }) { customer_id } }', headers);
  await request(base, `/${stack.project}/api/v1/customer?customer_id=eq.bogus`, { headers: { ...headers, ...PROFILE } });
  await graphql(base, '{ doesNotExist }', headers);
  const session = openSocket(base, change ? {} : headers);
  await new Promise((resolve) => setTimeout(resolve, 1500));
  session.socket.send(JSON.stringify({ id: 'mix', type: 'subscribe', payload: { query: 'subscription { hanaCustomerChanges { operation } }' } }));
  await new Promise((resolve) => setTimeout(resolve, 1500));
  if (change) {
    await request(base, `/${stack.project}/api/v1/customer`, { method: 'POST', headers: { ...headers, ...PROFILE }, body: { first_name: 'Mix', last_name: 'Change' } });
    await waitUntil(() => session.frames.some((frame) => frame.type === 'next'), { timeoutMs: 20000, label: 'a delivered change' });
  }
  session.socket.close();
  await session.closed;
}

module.exports = { sendKnownMix, graphql, openSocket, PROFILE };
