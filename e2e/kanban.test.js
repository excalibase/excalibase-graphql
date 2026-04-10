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
