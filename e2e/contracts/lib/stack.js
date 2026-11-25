const HOST = process.env.CONTRACT_HOST || '127.0.0.1';

const stack = {
  project: process.env.CONTRACT_PROJECT || 'e2e-test',
  app: `http://${HOST}:${process.env.CONTRACT_APP_PORT || 14000}`,
  management: `http://${HOST}:${process.env.CONTRACT_MANAGEMENT_PORT || 14081}`,
  requestMetrics: `http://${HOST}:${process.env.CONTRACT_REQUEST_METRICS_PORT || 14464}`,
  overload: `http://${HOST}:${process.env.CONTRACT_OVERLOAD_PORT || 14100}`,
  overloadManagement: `http://${HOST}:${process.env.CONTRACT_OVERLOAD_MANAGEMENT_PORT || 14181}`,
  vault: `http://${HOST}:${process.env.CONTRACT_VAULT_PORT || 14080}`,
  network: 'excalibase-contracts-net',
  image: process.env.ENGINE_IMAGE || 'excalibase/excalibase-graphql',
  hmacSecret: 'contract-test-hmac-secret-0123456789abcdef',
  otelOutDir: process.env.CONTRACT_OTEL_OUT || `${__dirname}/../out`,
};

module.exports = { stack };
