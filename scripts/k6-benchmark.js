import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('graphql_errors');
const queryDuration = new Trend('query_duration', true);
const queriesExecuted = new Counter('queries_executed');

// Configuration
const BASE_URL = __ENV.BASE_URL || 'http://localhost:10002';
const GRAPHQL_ENDPOINT = `${BASE_URL}/graphql`;

// Test scenarios - configure load profile
export const options = {
  scenarios: {
    // Warmup phase - let JVM optimize
    warmup: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      gracefulStop: '5s',
      tags: { phase: 'warmup' },
    },
    // Ramp up to find limits
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '2m', target: 200 },
      ],
      startTime: '35s', // Start after warmup
      gracefulStop: '10s',
      tags: { phase: 'ramp_up' },
    },
    // Sustained load test
    sustained_load: {
      executor: 'constant-vus',
      vus: 100,
      duration: '5m',
      startTime: '6m35s', // Start after ramp_up
      gracefulStop: '10s',
      tags: { phase: 'sustained' },
    },
    // Spike test
    spike: {
      executor: 'ramping-vus',
      startVUs: 100,
      stages: [
        { duration: '10s', target: 500 },
        { duration: '30s', target: 500 },
        { duration: '10s', target: 100 },
      ],
      startTime: '11m45s', // Start after sustained
      gracefulStop: '10s',
      tags: { phase: 'spike' },
    },
  },
  thresholds: {
    // Response time thresholds
    'http_req_duration': ['p(95)<2000', 'p(99)<5000'], // 95% under 2s, 99% under 5s
    'http_req_duration{phase:sustained}': ['p(95)<1500'], // Sustained load stricter

    // Error rate thresholds
    'http_req_failed': ['rate<0.01'], // Less than 1% errors
    'graphql_errors': ['rate<0.01'], // Less than 1% GraphQL errors

    // Throughput thresholds (only for sustained phase)
    'http_reqs{phase:sustained}': ['rate>100'], // At least 100 RPS sustained
  },
};

// GraphQL query templates
const QUERIES = {
  // Simple query - baseline performance
  simple: {
    name: 'Simple Employee Query',
    weight: 30,
    query: `{
      employees(limit: 10) {
        id
        first_name
        last_name
        email
      }
    }`,
  },

  // Filtered query with indexes
  filtered: {
    name: 'Filtered Employee Query',
    weight: 25,
    query: `{
      employees(
        where: {
          salary: { gte: 50000, lte: 100000 }
          employee_level: { gte: 3 }
        }
        limit: 50
      ) {
        id
        first_name
        last_name
        salary
        employee_level
      }
    }`,
  },

  // Relationship query - 2 levels
  relationships: {
    name: 'Employee with Relationships',
    weight: 20,
    query: `{
      employees(
        where: { salary: { gte: 75000 } }
        limit: 30
      ) {
        id
        first_name
        last_name
        departments {
          name
          budget
        }
        companies {
          name
          industry
        }
      }
    }`,
  },

  // Deep relationship query - 3+ levels
  deep_relationships: {
    name: 'Deep Relationship Query',
    weight: 15,
    query: `{
      time_entries(
        where: {
          hours_worked: { gte: 8 }
          entry_date: { gte: "2023-06-01", lte: "2023-12-31" }
        }
        limit: 25
      ) {
        id
        hours_worked
        entry_date
        employees {
          first_name
          last_name
          departments {
            name
            companies {
              name
              industry
            }
          }
        }
        projects {
          name
          budget
        }
      }
    }`,
  },

  // Large result set
  large_result: {
    name: 'Large Result Set',
    weight: 5,
    query: `{
      employees(limit: 100) {
        id
        first_name
        last_name
        email
        salary
        skills
        certifications
        address
      }
    }`,
  },

  // Complex filtering
  complex_filter: {
    name: 'Complex Filtering',
    weight: 5,
    query: `{
      companies(
        where: {
          industry: { eq: "Technology" }
          revenue: { gte: 1000000 }
        }
        limit: 50
      ) {
        id
        name
        industry
        revenue
        departments {
          name
          budget
        }
      }
    }`,
  },
};

// Weighted random query selection
function selectQuery() {
  const queryList = Object.values(QUERIES);
  const totalWeight = queryList.reduce((sum, q) => sum + q.weight, 0);
  let random = Math.random() * totalWeight;

  for (const query of queryList) {
    random -= query.weight;
    if (random <= 0) {
      return query;
    }
  }

  return queryList[0]; // Fallback
}

// Execute GraphQL query
function executeQuery(query) {
  const payload = JSON.stringify({
    query: query.query,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    tags: {
      query_name: query.name,
      query_type: query.name.split(' ')[0].toLowerCase(),
    },
  };

  const response = http.post(GRAPHQL_ENDPOINT, payload, params);

  // Check HTTP response
  const httpOk = check(response, {
    'status is 200': (r) => r.status === 200,
    'response time < 5000ms': (r) => r.timings.duration < 5000,
  });

  // Check GraphQL response
  let graphqlOk = false;
  let hasData = false;

  try {
    const body = JSON.parse(response.body);

    graphqlOk = check(body, {
      'no GraphQL errors': (b) => !b.errors || b.errors.length === 0,
    });

    hasData = check(body, {
      'has data field': (b) => b.data !== undefined && b.data !== null,
    });

    errorRate.add(!graphqlOk);

  } catch (e) {
    console.error(`Failed to parse response: ${e.message}`);
    errorRate.add(1);
  }

  // Track metrics
  if (httpOk && graphqlOk && hasData) {
    queryDuration.add(response.timings.duration);
    queriesExecuted.add(1);
  }

  return response;
}

// Main test function
export default function () {
  const query = selectQuery();
  executeQuery(query);

  // Small random delay to simulate real user behavior
  sleep(Math.random() * 0.5 + 0.1); // 0.1-0.6 seconds
}

// Setup function - runs once per VU
export function setup() {
  console.log('🚀 Starting K6 GraphQL Benchmark');
  console.log(`📍 Target: ${GRAPHQL_ENDPOINT}`);
  console.log(`⚙️  Scenarios: warmup → ramp_up → sustained → spike`);

  // Health check - try Spring Boot actuator endpoint
  const healthCheck = http.get(`${BASE_URL}/actuator/health`);

  if (healthCheck.status !== 200) {
    console.error('❌ Health check failed!');
    console.error(`Status: ${healthCheck.status}`);
    console.error(`Body: ${healthCheck.body}`);
    throw new Error('Server not healthy');
  }

  console.log('✅ Server health check passed');
  return { startTime: Date.now() };
}

// Teardown function - runs once at the end
export function teardown(data) {
  const duration = (Date.now() - data.startTime) / 1000;
  console.log('');
  console.log('🏁 Benchmark Complete');
  console.log(`⏱️  Total duration: ${duration.toFixed(2)}s`);
  console.log('📊 Check detailed metrics in the summary above');
}

// Custom summary for better reporting
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19);
  const frameworkName = __ENV.FRAMEWORK_NAME || 'benchmark';
  const summaryFile = `${frameworkName}-results-${timestamp}.json`;

  // Save full results as JSON
  const jsonResults = JSON.stringify(data, null, 2);

  // Generate human-readable summary
  const summary = generateSummary(data);

  console.log('\n' + '='.repeat(80));
  console.log(`📊 BENCHMARK SUMMARY [${frameworkName.toUpperCase()}]`);
  console.log('='.repeat(80));
  console.log(summary);
  console.log('='.repeat(80));

  return {
    'stdout': summary,
    [`scripts/results/${summaryFile}`]: jsonResults,
    [`scripts/results/${frameworkName}-latest.json`]: jsonResults,
  };
}

function generateSummary(data) {
  const metrics = data.metrics;
  let output = '\n';

  // Overall statistics
  output += '🎯 Overall Performance:\n';
  output += `   Total Requests: ${metrics.http_reqs?.values?.count || 0}\n`;
  output += `   Failed Requests: ${metrics.http_req_failed?.values?.passes || 0} (${(metrics.http_req_failed?.values?.rate * 100 || 0).toFixed(2)}%)\n`;
  output += `   GraphQL Errors: ${(metrics.graphql_errors?.values?.rate * 100 || 0).toFixed(2)}%\n`;
  output += `   Throughput: ${metrics.http_reqs?.values?.rate.toFixed(2) || 0} req/s\n`;
  output += '\n';

  // Response time statistics
  output += '⚡ Response Times:\n';
  const duration = metrics.http_req_duration?.values;
  if (duration) {
    const formatValue = (val) => {
      if (val === undefined || val === null || isNaN(val)) return 'N/A';
      return val.toFixed(2) + 'ms';
    };

    output += `   Min:  ${formatValue(duration.min)}\n`;
    output += `   Avg:  ${formatValue(duration.avg)}\n`;
    output += `   Med:  ${formatValue(duration.med)}\n`;
    output += `   p90:  ${formatValue(duration['p(90)'])}\n`;
    output += `   p95:  ${formatValue(duration['p(95)'])}\n`;
    output += `   p99:  ${formatValue(duration['p(99)'])}\n`;
    output += `   Max:  ${formatValue(duration.max)}\n`;
  }
  output += '\n';

  // Phase breakdown
  output += '📈 Performance by Phase:\n';
  ['warmup', 'ramp_up', 'sustained', 'spike'].forEach(phase => {
    const phaseDuration = metrics[`http_req_duration{phase:${phase}}`]?.values;
    const phaseReqs = metrics[`http_reqs{phase:${phase}}`]?.values;

    if (phaseDuration && phaseReqs) {
      output += `   ${phase.toUpperCase()}:\n`;
      output += `      Requests: ${phaseReqs.count || 0} (${(phaseReqs.rate || 0).toFixed(2)} req/s)\n`;
      output += `      p95: ${(phaseDuration['p(95)'] || 0).toFixed(2)}ms\n`;
      output += `      p99: ${(phaseDuration['p(99)'] || 0).toFixed(2)}ms\n`;
    }
  });
  output += '\n';

  // Threshold results
  output += '✅ Threshold Results:\n';
  const thresholds = data.root_group?.checks || [];
  Object.entries(data.thresholds || {}).forEach(([name, threshold]) => {
    const passed = threshold.ok ? '✅' : '❌';
    output += `   ${passed} ${name}\n`;
  });

  return output;
}
