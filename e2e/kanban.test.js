const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('./client');

const GRAPHQL_URL = process.env.SC_GRAPHQL_URL || 'http://localhost:10004/graphql';
const AUTH_URL = process.env.SC_AUTH_URL || 'http://localhost:24004/auth';
const REST_URL = GRAPHQL_URL.replace('/graphql', '/api/v1');

const PROJECT = { orgSlug: 'study-cases', projectName: 'kanban' };
const TEST_USER = { email: 'pm@example.com', password: 'Pass123!', fullName: 'E2E PM' };

let token;
let client;

async function authPost(path, body) {
  const res = await fetch(`${AUTH_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function waitForAuth() {
  for (let i = 0; i < 30; i++) {
    try {
      const r = await fetch(`${AUTH_URL.replace('/auth', '')}/healthz`, { signal: AbortSignal.timeout(3000) });
      if (r.ok) return;
    } catch (_) {}
    await new Promise(r => setTimeout(r, 3000));
  }
  throw new Error('Auth service not ready');
}

async function registerAndLogin() {
  const base = `/${PROJECT.orgSlug}/${PROJECT.projectName}`;
  const reg = await authPost(`${base}/register`, { email: TEST_USER.email, password: TEST_USER.password, fullName: TEST_USER.fullName });
  if (reg.status !== 200 && reg.status !== 201 && reg.status !== 409) {
    throw new Error(`Register failed (${reg.status}): ${JSON.stringify(reg.data)}`);
  }
  const login = await authPost(`${base}/login`, { email: TEST_USER.email, password: TEST_USER.password });
  if (!login.data.accessToken) {
    throw new Error(`Login failed: ${JSON.stringify(login.data)}`);
  }
  return login.data.accessToken;
}

beforeAll(async () => {
  await Promise.all([
    waitForAuth(),
    waitForApi(GRAPHQL_URL, { maxRetries: 30, delayMs: 3000 }),
  ]);
  token = await registerAndLogin();
  client = new GraphQLClient(GRAPHQL_URL, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

async function restGet(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, { headers: { 'Accept-Profile': 'kanban', Authorization: `Bearer ${token}`, ...headers } });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}
async function restPost(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'kanban', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}
async function restPatch(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'kanban', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

// ─── GraphQL: Organization & Projects ────────────────────────────────────────

describe('Kanban GraphQL — Organization', () => {
  test('list organizations with plan enum', async () => {
    const data = await client.request(gql`{
      kanbanOrganizations { id name slug plan }
    }`);
    expect(data.kanbanOrganizations).toHaveLength(2);
    expect(data.kanbanOrganizations[0].plan).toBe('ENTERPRISE');
  });

  test('org → users (reverse FK)', async () => {
    const data = await client.request(gql`{
      kanbanOrganizations(where: { slug: { eq: "acme" } }) {
        name
        kanbanUsers { id name role email }
      }
    }`);
    expect(data.kanbanOrganizations[0].kanbanUsers.length).toBe(3);
  });

  test('org → projects (reverse FK)', async () => {
    const data = await client.request(gql`{
      kanbanOrganizations(where: { slug: { eq: "acme" } }) {
        kanbanProjects { id name key }
      }
    }`);
    const projects = data.kanbanOrganizations[0].kanbanProjects;
    expect(projects.length).toBeGreaterThanOrEqual(2);
  });
});

// ─── GraphQL: Issues & FK Chains ─────────────────────────────────────────────

describe('Kanban GraphQL — Issues', () => {
  test('issue → assignee + reporter (2 FKs to users)', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 3 } }) {
        title priority status
        kanbanAssigneeId { name }
        kanbanReporterId { name }
      }
    }`);
    const issue = data.kanbanIssues[0];
    expect(issue.title).toBe('REST filter operators');
    expect(issue.kanbanAssigneeId.name).toBe('Alice Chen');
    expect(issue.kanbanReporterId.name).toBe('Bob Kumar');
  });

  test('issue → parent_issue (self-ref for subtasks)', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 4 } }) {
        title
        kanbanParentIssueId { id title }
      }
    }`);
    expect(data.kanbanIssues[0].kanbanParentIssueId.title).toBe('REST filter operators');
  });

  test('nullable FK: issue without sprint', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 7 } }) {
        title sprint_id
      }
    }`);
    expect(data.kanbanIssues[0].sprint_id).toBeFalsy();
  });

  test('nullable FK: unassigned issue', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 6 } }) {
        title assignee_id
      }
    }`);
    expect(data.kanbanIssues[0].assignee_id).toBeFalsy();
  });

  test('filter by ENUM priority', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { priority: { eq: "critical" } }) { id title }
    }`);
    expect(data.kanbanIssues.length).toBeGreaterThanOrEqual(2);
  });

  test('filter by ENUM status', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { status: { eq: "in_progress" } }) { id title }
    }`);
    expect(data.kanbanIssues.length).toBeGreaterThanOrEqual(3);
  });

  test('issue → comments → author (3-level FK)', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 3 } }) {
        title
        kanbanComments { body kanbanAuthorId { name } }
      }
    }`);
    const comments = data.kanbanIssues[0].kanbanComments;
    expect(comments.length).toBeGreaterThanOrEqual(3);
  });

  test('issue_labels many-to-many junction', async () => {
    const data = await client.request(gql`{
      kanbanIssueLabels { issue_id label_id }
    }`);
    expect(data.kanbanIssueLabels.length).toBeGreaterThanOrEqual(15);
  });

  test('aggregate: issue count', async () => {
    const data = await client.request(gql`{
      kanbanIssuesAggregate { count }
    }`);
    expect(data.kanbanIssuesAggregate.count).toBeGreaterThanOrEqual(15);
  });

  test('computed field: issue age', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { id: { eq: 1 } }) { id title issue_age }
    }`);
    expect(data.kanbanIssues[0].issue_age).toBeGreaterThanOrEqual(0);
  });
});

describe('Kanban GraphQL — Full-text search', () => {
  test('search finds issues by a distinctive term in the description', async () => {
    // "stripe" only appears in the payment + webhook issues
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { search: "stripe" } }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles).toEqual(expect.arrayContaining(['Payment integration', 'Stripe webhook handler']));
    expect(titles).not.toContain('Setup JWT auth');
  });

  test('search matches title terms too (title||description is indexed)', async () => {
    // "benchmarks" appears in the title "Performance benchmarks"
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { search: "benchmarks" } }) { id title }
    }`);
    expect(data.kanbanIssues.length).toBeGreaterThanOrEqual(1);
    expect(data.kanbanIssues[0].title).toBe('Performance benchmarks');
  });

  test('search uses english stemming', async () => {
    // "query" should stem to match "queries" in the range operators description
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { search: "queries" } }) { id title }
    }`);
    expect(data.kanbanIssues.length).toBeGreaterThanOrEqual(1);
  });

  test('search combines with other filters via implicit AND', async () => {
    // high-priority issues that mention "payment"
    const data = await client.request(gql`{
      kanbanIssues(where: {
        search_vec: { search: "payment" },
        priority: { eq: "critical" }
      }) { id title priority }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles).toContain('Payment integration');
    // A non-critical issue that would otherwise match must not appear.
    // GraphQL emits enum values uppercase (CRITICAL), REST emits lowercase.
    for (const issue of data.kanbanIssues) {
      expect(issue.priority.toLowerCase()).toBe('critical');
    }
  });

  test('search returns empty set for unmatched query', async () => {
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { search: "xyznomatch" } }) { id title }
    }`);
    expect(data.kanbanIssues).toEqual([]);
  });

  test('webSearch OR matches either term', async () => {
    // "stripe" only hits payment issues, "benchmarks" only hits the perf issue
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { webSearch: "stripe OR benchmarks" } }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles).toEqual(expect.arrayContaining([
      'Payment integration',
      'Stripe webhook handler',
      'Performance benchmarks',
    ]));
  });

  test('webSearch minus-prefix excludes rows', async () => {
    // "payment -stripe" → has "payment", does NOT have "stripe".
    // Issue 12 ("Payment integration") mentions both → excluded.
    // Issue 13 ("Email notifications") has "payment" but no "stripe" → match.
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { webSearch: "payment -stripe" } }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles).toContain('Email notifications');
    expect(titles).not.toContain('Payment integration');
    expect(titles).not.toContain('Stripe webhook handler');
  });

  test('webSearch exact phrase via quotes', async () => {
    // "webhook handler" appears adjacent in the title "Stripe webhook handler".
    // The phrase operator requires adjacency, so rows that mention the two
    // words in separate places won't match. We use String.raw so the backslash
    // escape passes through JS untouched — GraphQL itself interprets the
    // \" as a literal " inside the string literal.
    const data = await client.request(String.raw`{
      kanbanIssues(where: { search_vec: { webSearch: "\"webhook handler\"" } }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles).toContain('Stripe webhook handler');
  });

  test('webSearch is safe against malformed input', async () => {
    // websearch_to_tsquery silently tolerates junk that would throw with
    // raw to_tsquery. Query must not error at the server.
    const data = await client.request(gql`{
      kanbanIssues(where: { search_vec: { webSearch: "(((" } }) { id title }
    }`);
    expect(data.kanbanIssues).toBeDefined();
  });
});

describe('Kanban GraphQL — Vector k-NN search', () => {
  test('vector L2 near payment axis returns the payment cluster', async () => {
    // Embedding axis 3 = payment. Query near [0,0,1] must rank payment-
    // themed issues ahead of auth/filter issues.
    const data = await client.request(gql`{
      kanbanIssues(vector: {
        column: "embedding"
        near: [0.0, 0.0, 1.0]
        distance: "L2"
        limit: 3
      }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles.length).toBe(3);
    expect(titles[0]).toBe('Payment integration');  // [0,0,1] — exact match
    expect(titles[1]).toBe('Stripe webhook handler'); // [0,0,0.95] — next nearest
    // Third slot is another payment-adjacent issue, not auth or filter
    expect(titles[2]).toMatch(/Email notifications|Analytics dashboard|Landing page/);
  });

  test('vector L2 near auth axis returns the auth cluster', async () => {
    const data = await client.request(gql`{
      kanbanIssues(vector: {
        column: "embedding"
        near: [1.0, 0.0, 0.0]
        distance: "L2"
        limit: 2
      }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    expect(titles[0]).toBe('Setup JWT auth');       // [1,0,0]
    expect(titles[1]).toBe('User CRUD endpoints');  // [0.9,0.1,0]
  });

  test('vector limit clamps the result set', async () => {
    const data = await client.request(gql`{
      kanbanIssues(vector: {
        column: "embedding"
        near: [0.0, 0.0, 1.0]
        distance: "L2"
        limit: 1
      }) { id title }
    }`);
    expect(data.kanbanIssues).toHaveLength(1);
    expect(data.kanbanIssues[0].title).toBe('Payment integration');
  });

  test('vector overrides user orderBy — k-NN ordering wins', async () => {
    // id DESC would put 15 first but the payment query puts 12 first.
    const data = await client.request(gql`{
      kanbanIssues(
        vector: { column: "embedding", near: [0.0, 0.0, 1.0], distance: "L2", limit: 3 }
        orderBy: { id: DESC }
      ) { id title }
    }`);
    expect(data.kanbanIssues[0].title).toBe('Payment integration');
  });

  test('vector COSINE distance clusters by direction', async () => {
    // Cosine near [0,0,1] should cluster payment-direction rows regardless
    // of their magnitude.
    const data = await client.request(gql`{
      kanbanIssues(vector: {
        column: "embedding"
        near: [0.0, 0.0, 1.0]
        distance: "COSINE"
        limit: 2
      }) { id title }
    }`);
    const titles = data.kanbanIssues.map(i => i.title);
    // Both "Payment integration" and "Stripe webhook handler" are on the
    // [0,0,1] axis so cosine distance is 0 for both.
    expect(titles).toEqual(expect.arrayContaining(['Payment integration', 'Stripe webhook handler']));
  });
});

describe('Kanban REST — Full-text search', () => {
  test('plfts matches a distinctive term in the description', async () => {
    const r = await restGet('/issues?select=id,title&description=plfts.stripe');
    expect(r.status).toBe(200);
    const titles = r.data.data.map(i => i.title);
    expect(titles).toEqual(expect.arrayContaining(['Payment integration', 'Stripe webhook handler']));
  });

  test('plfts combines with an eq filter', async () => {
    const r = await restGet('/issues?select=id,title,priority&description=plfts.payment&priority=eq.critical');
    expect(r.status).toBe(200);
    const titles = r.data.data.map(i => i.title);
    expect(titles).toContain('Payment integration');
    for (const issue of r.data.data) expect(issue.priority).toBe('critical');
  });

  test('plfts returns empty for unmatched term', async () => {
    const r = await restGet('/issues?select=id,title&description=plfts.xyznomatch');
    expect(r.status).toBe(200);
    expect(r.data.data).toEqual([]);
  });
});

describe('Kanban REST — Vector k-NN search', () => {
  // The JSON must be URL-encoded so Jetty can parse the query string cleanly.
  const vectorParam = (obj) => `embedding=vector.${encodeURIComponent(JSON.stringify(obj))}`;

  test('vector L2 near payment axis returns the payment cluster', async () => {
    const r = await restGet(`/issues?select=id,title&${vectorParam({ near: [0.0, 0.0, 1.0], distance: 'L2', limit: 3 })}`);
    expect(r.status).toBe(200);
    const titles = r.data.data.map(i => i.title);
    expect(titles.length).toBe(3);
    expect(titles[0]).toBe('Payment integration');
    expect(titles[1]).toBe('Stripe webhook handler');
  });

  test('vector L2 near auth axis returns the auth cluster', async () => {
    const r = await restGet(`/issues?select=id,title&${vectorParam({ near: [1.0, 0.0, 0.0], distance: 'L2', limit: 2 })}`);
    expect(r.status).toBe(200);
    const titles = r.data.data.map(i => i.title);
    expect(titles[0]).toBe('Setup JWT auth');
    expect(titles[1]).toBe('User CRUD endpoints');
  });

  test('vector combines with a WHERE predicate', async () => {
    const r = await restGet(`/issues?select=id,title,priority&${vectorParam({ near: [0.0, 0.0, 1.0], distance: 'L2', limit: 10 })}&priority=eq.high`);
    expect(r.status).toBe(200);
    for (const issue of r.data.data) expect(issue.priority).toBe('high');
    // First high-priority row near [0,0,1] is Stripe webhook handler.
    expect(r.data.data[0].title).toBe('Stripe webhook handler');
  });

  test('vector IP (inner product) ranks by dot product', async () => {
    const r = await restGet(`/issues?select=id,title&${vectorParam({ near: [1.0, 1.0, 1.0], distance: 'IP', limit: 3 })}`);
    expect(r.status).toBe(200);
    expect(r.data.data.length).toBe(3);
  });
});

// ─── GraphQL: Views ──────────────────────────────────────────────────────────

describe('Kanban GraphQL — Views', () => {
  test('sprint_board view shows active sprint issues', async () => {
    const data = await client.request(gql`{
      kanbanSprintBoard { id title status priority assignee_name sprint_name }
    }`);
    expect(data.kanbanSprintBoard.length).toBeGreaterThanOrEqual(5);
  });

  test('user_workload view shows assigned count', async () => {
    const data = await client.request(gql`{
      kanbanUserWorkload { name assigned_issues total_points }
    }`);
    expect(data.kanbanUserWorkload.length).toBeGreaterThanOrEqual(6);
  });
});

// ─── REST API ────────────────────────────────────────────────────────────────

describe('Kanban REST — Read', () => {
  test('GET /issues with status filter', async () => {
    const res = await restGet('/issues?status=eq.in_progress&order=priority.asc');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(3);
  });

  test('GET /issues with Prefer: count=exact', async () => {
    const res = await restGet('/issues', { 'Prefer': 'count=exact' });
    expect(res.status).toBe(200);
    expect(res.data.pagination.total).toBeGreaterThanOrEqual(15);
  });

  test('GET /sprint_board view', async () => {
    const res = await restGet('/sprint_board');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(5);
  });

  test('GET /user_workload view', async () => {
    const res = await restGet('/user_workload');
    expect(res.status).toBe(200);
  });

  test('GET /issues cursor pagination', async () => {
    const res = await restGet('/issues?first=5&order=id.asc');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(5);
    expect(res.data.pageInfo.hasNextPage).toBe(true);
  });
});

describe('Kanban REST — Mutations', () => {
  let newIssueId;

  test('POST /issues creates issue', async () => {
    const res = await restPost('/issues', {
      project_id: 1, reporter_id: 1, title: 'E2E test issue',
      priority: 'medium', status: 'backlog', story_points: 3,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
    newIssueId = res.data.data.id;
  });

  test('PATCH /issues moves to in_progress', async () => {
    const res = await restPatch(`/issues?id=eq.${newIssueId}`, {
      status: 'in_progress', assignee_id: 2,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).toBe('in_progress');
  });

  test('POST /time_entries logs time', async () => {
    const res = await restPost('/time_entries', {
      issue_id: newIssueId, user_id: 2, hours: 2.5, description: 'E2E test work',
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
  });

  test('POST /issue_labels bulk add labels', async () => {
    const res = await restPost('/issue_labels', [
      { issue_id: newIssueId, label_id: 1 },
      { issue_id: newIssueId, label_id: 2 },
    ], { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
  });

  test('POST /issues with tx=rollback', async () => {
    const res = await restPost('/issues', {
      project_id: 1, reporter_id: 1, title: 'Should not persist',
      priority: 'low', status: 'backlog',
    }, { 'Prefer': 'return=representation, tx=rollback' });
    expect(res.status).toBe(201);
    const check = await restGet(`/issues?title=eq.Should not persist`);
    expect(check.data.data).toHaveLength(0);
  });
});
