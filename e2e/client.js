const { GraphQLClient } = require('graphql-request');

/**
 * Polls the GraphQL endpoint until it responds, then resolves.
 */
async function waitForApi(url, { maxRetries = 15, delayMs = 5000 } = {}) {
  for (let i = 1; i <= maxRetries; i++) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: '{ __typename }' }),
        signal: AbortSignal.timeout(5000),
      });
      if (res.status < 500) return; // API is up (even 400 means it parsed the request)
    } catch (_) {
      // not ready yet
    }
    if (i < maxRetries) {
      console.log(`[INFO] API not ready, attempt ${i}/${maxRetries} — retrying in ${delayMs / 1000}s...`);
      await new Promise(r => setTimeout(r, delayMs));
    }
  }
  throw new Error(`API at ${url} not ready after ${maxRetries} attempts`);
}

/**
 * Creates a GraphQLClient for the given URL and optional headers.
 */
function createClient(url, headers = {}) {
  return new GraphQLClient(url, { headers });
}

module.exports = { waitForApi, createClient };
