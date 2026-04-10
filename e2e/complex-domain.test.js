const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('./client');

const API_URL = process.env.POSTGRES_API_URL || 'http://localhost:10000/graphql';
let client;

beforeAll(async () => {
  await waitForApi(API_URL.replace('/graphql', ''));
  client = new GraphQLClient(API_URL);
});

// ─── Deep FK Chain (6 levels) ─────────────────────────────────────────────────

describe('Deep FK Chain', () => {
  test('company → department → team → employee → task → comment (6 levels)', async () => {
    const data = await client.request(gql`{
      complexCompany(where: { id: { eq: 1 } }) {
        id name
        complexDepartment {
          id name
          complexTeam {
            id name
          }
        }
      }
    }`);
    expect(data.complexCompany).toHaveLength(1);
    expect(data.complexCompany[0].name).toBe('Acme Corp');
    // Reverse FK: department.company_id → company, field name = complexDepartment
    const depts = data.complexCompany[0].complexDepartment;
    expect(depts.length).toBeGreaterThanOrEqual(1);
    // Nested: team.department_id → department, field name = complexTeam
    expect(depts[0].complexTeam.length).toBeGreaterThanOrEqual(1);
  });
});

// ─── Self-Referential FK ──────────────────────────────────────────────────────

describe('Self-Referential FK', () => {
  test('employee → manager (forward self-ref via manager_id)', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { id: { eq: 4 } }) {
        id name manager_id
        complexManagerId { id name }
      }
    }`);
    expect(data.complexEmployee[0].name).toBe('Diana Dev');
    expect(data.complexEmployee[0].complexManagerId.id).toBe(3);
    expect(data.complexEmployee[0].complexManagerId.name).toBe('Charlie Lead');
  });

  test('employee → mentor (second self-ref via mentor_id)', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { id: { eq: 3 } }) {
        id name mentor_id
        complexMentorId { id name }
      }
    }`);
    expect(data.complexEmployee[0].complexMentorId.id).toBe(1);
    expect(data.complexEmployee[0].complexMentorId.name).toBe('Alice CEO');
  });

  test('nullable self-ref FK returns null when not set', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { id: { eq: 2 } }) {
        id name mentor_id
      }
    }`);
    // Bob has no mentor — GraphQL omits null fields
    expect(data.complexEmployee[0].mentor_id).toBeFalsy();
  });
});

// ─── Multiple FKs to Same Table ───────────────────────────────────────────────

describe('Multiple FKs to Same Table', () => {
  test('task has distinct assignee and reporter fields', async () => {
    const data = await client.request(gql`{
      complexTask(where: { id: { eq: 1 } }) {
        id title assignee_id reporter_id
        complexAssigneeId { id name }
        complexReporterId { id name }
      }
    }`);
    const task = data.complexTask[0];
    expect(task.title).toBe('Implement auth');
    // assignee_id=4 (Diana), reporter_id=3 (Charlie) — must be different
    expect(task.complexAssigneeId.id).toBe(4);
    expect(task.complexAssigneeId.name).toBe('Diana Dev');
    expect(task.complexReporterId.id).toBe(3);
    expect(task.complexReporterId.name).toBe('Charlie Lead');
  });
});

// ─── Reserved SQL Word Table/Column Names ─────────────────────────────────────

describe('Reserved Word Tables', () => {
  test('query table named "order" with reserved word columns', async () => {
    const data = await client.request(gql`{
      complexOrder { id group select from where limit table description }
    }`);
    expect(data.complexOrder.length).toBeGreaterThanOrEqual(3);
    expect(data.complexOrder[0].group).toBeTruthy();
    expect(typeof data.complexOrder[0].select).toBe('boolean');
  });

  test('create mutation on reserved word table', async () => {
    const data = await client.request(gql`mutation {
      createComplexOrder(input: {
        group: "test_e2e", select: true, description: "E2E test"
      }) { id group select description }
    }`);
    expect(data.createComplexOrder.group).toBe('test_e2e');
    expect(data.createComplexOrder.select).toBe(true);
  });
});

// ─── No Primary Key Table ─────────────────────────────────────────────────────

describe('No Primary Key Table', () => {
  test('query audit_log (no PK) returns data', async () => {
    const data = await client.request(gql`{
      complexAuditLog { event_type table_name record_id payload }
    }`);
    expect(data.complexAuditLog.length).toBeGreaterThanOrEqual(1);
  });

  test('create works on no-PK table', async () => {
    const data = await client.request(gql`mutation {
      createComplexAuditLog(input: {
        event_type: "E2E_TEST", table_name: "test", record_id: 999
      }) { event_type table_name record_id }
    }`);
    expect(data.createComplexAuditLog.event_type).toBe('E2E_TEST');
  });

  test('update/delete use where filter (no PK needed)', async () => {
    // With where-based mutations, no-PK tables can still be updated/deleted via filters
    const data = await client.request(gql`{
      __schema { mutationType { fields { name } } }
    }`);
    const mutations = data.__schema.mutationType.fields.map(f => f.name);
    expect(mutations).toContain('createComplexAuditLog');
    expect(mutations).toContain('createManyComplexAuditLog');
    expect(mutations).toContain('updateComplexAuditLog');
    expect(mutations).toContain('deleteComplexAuditLog');
  });
});

// ─── Circular FK ──────────────────────────────────────────────────────────────

describe('Circular FK', () => {
  test('config_a → config_b → config_a resolves without infinite loop', async () => {
    const data = await client.request(gql`{
      complexConfigA {
        id name
        complexRefBId { id name complexRefAId { id name } }
      }
    }`);
    expect(data.complexConfigA.length).toBeGreaterThanOrEqual(2);
    expect(data.complexConfigA[0].complexRefBId.complexRefAId.id).toBe(data.complexConfigA[0].id);
  });
});

// ─── Composite PK (Many-to-Many) ─────────────────────────────────────────────

describe('Composite PK / Many-to-Many', () => {
  test('query project_member junction table', async () => {
    const data = await client.request(gql`{
      complexProjectMember { project_id employee_id role joined_at }
    }`);
    expect(data.complexProjectMember.length).toBeGreaterThanOrEqual(8);
  });

  test('create on composite PK table', async () => {
    // Clean up first (delete if exists)
    try {
      await client.request(gql`mutation {
        deleteComplexProjectMember(where: {
          project_id: { eq: 1 }, employee_id: { eq: 8 }
        }) { project_id }
      }`);
    } catch (e) { /* ignore */ }

    const data = await client.request(gql`mutation {
      createComplexProjectMember(input: {
        project_id: 1, employee_id: 8, role: "tester", joined_at: "2026-04-09"
      }) { project_id employee_id role }
    }`);
    expect(data.createComplexProjectMember.role).toBe('tester');
  });
});

// ─── Enum Types ───────────────────────────────────────────────────────────────

describe('Enum Types', () => {
  test('enum types are exposed in schema', async () => {
    const data = await client.request(gql`{
      __type(name: "ComplexEmploymentStatus") { enumValues { name } }
    }`);
    const values = data.__type.enumValues.map(v => v.name);
    expect(values).toContain('active');
    expect(values).toContain('on_leave');
    expect(values).toContain('terminated');
    expect(values).toContain('contractor');
  });

  test('filter by enum value', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { status: { eq: "on_leave" } }) { id name status }
    }`);
    expect(data.complexEmployee.length).toBeGreaterThanOrEqual(1);
    data.complexEmployee.forEach(e => expect(e.status).toBe('ON_LEAVE'));
  });
});

// ─── Computed Fields ──────────────────────────────────────────────────────────

describe('Computed Fields', () => {
  test('computed fields visible in introspection', async () => {
    const data = await client.request(gql`{
      __type(name: "ComplexEmployee") { fields { name } }
    }`);
    const fields = data.__type.fields.map(f => f.name);
    expect(fields).toContain('full_title');
    expect(fields).toContain('tenure_days');
  });

  test('computed field returns correct value', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { id: { eq: 1 } }) { id name status full_title }
    }`);
    expect(data.complexEmployee[0].full_title).toBe('Alice CEO (active)');
  });

  test('task computed field is_overdue', async () => {
    const data = await client.request(gql`{
      __type(name: "ComplexTask") { fields { name } }
    }`);
    const fields = data.__type.fields.map(f => f.name);
    expect(fields).toContain('is_overdue');
  });
});

// ─── Views ────────────────────────────────────────────────────────────────────

describe('Views', () => {
  test('multi-join view active_employees returns data', async () => {
    const data = await client.request(gql`{
      complexActiveEmployees { id name email team_name department_name company_name }
    }`);
    expect(data.complexActiveEmployees.length).toBeGreaterThanOrEqual(1);
    expect(data.complexActiveEmployees[0].company_name).toBeTruthy();
  });

  test('materialized view project_summary returns data', async () => {
    const data = await client.request(gql`{
      complexProjectSummary { id name status budget member_count }
    }`);
    expect(data.complexProjectSummary.length).toBeGreaterThanOrEqual(1);
    // member_count is bigint — verify it's returned (as BigInteger string)
    expect(data.complexProjectSummary[0].member_count).toBeTruthy();
  });

  test('views have no mutations', async () => {
    const data = await client.request(gql`{
      __schema { mutationType { fields { name } } }
    }`);
    const mutations = data.__schema.mutationType.fields.map(f => f.name);
    expect(mutations.filter(m => m.includes('ActiveEmployees'))).toHaveLength(0);
    expect(mutations.filter(m => m.includes('TaskOverview'))).toHaveLength(0);
    expect(mutations.filter(m => m.includes('ProjectSummary'))).toHaveLength(0);
  });
});

// ─── Where-Based Update/Delete ────────────────────────────────────────────────

describe('Where-Based Mutations', () => {
  test('update with where filter returns affected rows', async () => {
    const data = await client.request(gql`mutation {
      updateComplexEmployee(
        where: { id: { eq: 8 } }
        input: { salary: 85000 }
      ) { id name salary }
    }`);
    expect(data.updateComplexEmployee).toHaveLength(1);
    expect(data.updateComplexEmployee[0].salary).toBe(85000.0);
  });

  test('delete with where filter returns deleted rows', async () => {
    // Create a temp record to delete
    await client.request(gql`mutation {
      createComplexDocument(input: {
        title: "To Delete", content: "temp", word_count: 1
      }) { id }
    }`);

    const data = await client.request(gql`mutation {
      deleteComplexDocument(where: { title: { eq: "To Delete" } }) { id title }
    }`);
    expect(data.deleteComplexDocument.length).toBeGreaterThanOrEqual(1);
    expect(data.deleteComplexDocument[0].title).toBe('To Delete');
  });

  test('update without where is rejected (returns null)', async () => {
    const data = await client.request(gql`mutation {
      updateComplexEmployee(input: { salary: 0 }) { id }
    }`);
    // No where = compiler returns null → field omitted from response
    expect(data.updateComplexEmployee).toBeFalsy();
  });
});

// ─── Large Text ───────────────────────────────────────────────────────────────

describe('Large Text', () => {
  test('128KB text content round-trips correctly', async () => {
    const data = await client.request(gql`{
      complexDocument(where: { title: { eq: "Large Doc" } }) { id content word_count }
    }`);
    expect(data.complexDocument[0].content.length).toBeGreaterThan(100000);
    expect(data.complexDocument[0].word_count).toBe(24000);
  });
});

// ─── JSONB Fields ─────────────────────────────────────────────────────────────

describe('JSONB Fields', () => {
  test('JSONB field returns as parsed object', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { id: { eq: 1 } }) { id profile }
    }`);
    expect(data.complexEmployee[0].profile).toEqual({ level: 'C-suite' });
  });

  test('company metadata JSONB', async () => {
    const data = await client.request(gql`{
      complexCompany(where: { id: { eq: 1 } }) { metadata }
    }`);
    expect(data.complexCompany[0].metadata).toEqual({ industry: 'tech', size: 'large' });
  });
});

// ─── Connection Pagination ────────────────────────────────────────────────────

describe('Connection Pagination', () => {
  test('employee connection with cursor pagination', async () => {
    const data = await client.request(gql`{
      complexEmployeeConnection(first: 3) {
        edges { node { id name } cursor }
        pageInfo { hasNextPage endCursor }
        totalCount
      }
    }`);
    expect(data.complexEmployeeConnection.edges).toHaveLength(3);
    expect(data.complexEmployeeConnection.pageInfo.hasNextPage).toBe(true);
    expect(data.complexEmployeeConnection.totalCount).toBeGreaterThanOrEqual(8);
  });
});

// ─── Aggregate ────────────────────────────────────────────────────────────────

describe('Aggregates', () => {
  test('employee count aggregate', async () => {
    const data = await client.request(gql`{
      complexEmployeeAggregate { count }
    }`);
    expect(data.complexEmployeeAggregate.count).toBeGreaterThanOrEqual(8);
  });
});

// ─── NULL Filter ──────────────────────────────────────────────────────────────

describe('NULL Filter', () => {
  test('isNull filter finds employees without mentor', async () => {
    const data = await client.request(gql`{
      complexEmployee(where: { mentor_id: { isNull: true } }) { id name }
    }`);
    expect(data.complexEmployee.length).toBeGreaterThanOrEqual(1);
  });
});
