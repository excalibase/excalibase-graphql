const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('./client');

const GRAPHQL_URL = process.env.SC_GRAPHQL_URL || 'http://localhost:10004/graphql';
const AUTH_URL = process.env.SC_AUTH_URL || 'http://localhost:24004/auth';
const REST_URL = GRAPHQL_URL.replace('/graphql', '/api/v1');

const PROJECT = { orgSlug: 'study-cases', projectName: 'clinic' };
const TEST_USER = { email: 'clinician@example.com', password: 'Pass123!', fullName: 'E2E Clinician' };

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
  const res = await fetch(`${REST_URL}${path}`, { headers: { 'Accept-Profile': 'clinic', Authorization: `Bearer ${token}`, ...headers } });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}
async function restPost(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'clinic', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}
async function restPatch(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'clinic', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

// ─── GraphQL: Doctors & Specializations ──────────────────────────────────────

describe('Clinic GraphQL — Doctors', () => {
  test('list doctors with specialization FK', async () => {
    const data = await client.request(gql`{
      clinicDoctors { id name email clinicSpecializationId { name } }
    }`);
    expect(data.clinicDoctors.length).toBe(6);
    const sarah = data.clinicDoctors.find(d => d.name === 'Dr. Sarah Kim');
    expect(sarah.clinicSpecializationId.name).toBe('Cardiology');
  });

  test('doctor → availability slots (reverse FK)', async () => {
    const data = await client.request(gql`{
      clinicDoctors(where: { id: { eq: 1 } }) {
        name
        clinicDoctorAvailability { day_of_week start_time end_time }
      }
    }`);
    const slots = data.clinicDoctors[0].clinicDoctorAvailability;
    expect(slots.length).toBe(3);
  });
});

// ─── GraphQL: Patients ───────────────────────────────────────────────────────

describe('Clinic GraphQL — Patients', () => {
  test('patient with array allergies field', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 1 } }) { name allergies blood_type }
    }`);
    expect(data.clinicPatients[0].allergies).toContain('penicillin');
  });

  test('patient with JSONB emergency_contact', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 1 } }) { name emergency_contact }
    }`);
    expect(data.clinicPatients[0].emergency_contact.relation).toBe('spouse');
  });

  test('patient with null emergency_contact', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 3 } }) { name emergency_contact }
    }`);
    expect(data.clinicPatients[0].emergency_contact).toBeFalsy();
  });

  test('patient with multiple allergies', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 9 } }) { name allergies }
    }`);
    expect(data.clinicPatients[0].allergies.length).toBe(2);
  });

  test('computed field: patient age', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 1 } }) { name patient_age }
    }`);
    expect(data.clinicPatients[0].patient_age).toBeGreaterThanOrEqual(40);
  });
});

// ─── GraphQL: Appointments & FK Chains ───────────────────────────────────────

describe('Clinic GraphQL — Appointments', () => {
  test('appointment → patient + doctor (2 FKs)', async () => {
    const data = await client.request(gql`{
      clinicAppointments(where: { id: { eq: 1 } }) {
        scheduled_at status notes
        clinicPatientId { name }
        clinicDoctorId { name clinicSpecializationId { name } }
      }
    }`);
    const apt = data.clinicAppointments[0];
    expect(apt.clinicPatientId.name).toBe('John Doe');
    expect(apt.clinicDoctorId.name).toBe('Dr. Sarah Kim');
    expect(apt.clinicDoctorId.clinicSpecializationId.name).toBe('Cardiology');
  });

  test('patient → appointments → diagnoses (3-level FK)', async () => {
    const data = await client.request(gql`{
      clinicPatients(where: { id: { eq: 1 } }) {
        name
        clinicAppointments { id status clinicDiagnoses { icd_code description severity } }
      }
    }`);
    const appointments = data.clinicPatients[0].clinicAppointments;
    expect(appointments.length).toBeGreaterThanOrEqual(2);
  });

  test('filter by ENUM status', async () => {
    const data = await client.request(gql`{
      clinicAppointments(where: { status: { eq: "completed" } }) { id }
    }`);
    expect(data.clinicAppointments.length).toBeGreaterThanOrEqual(12);
  });

  test('cancelled appointment has cancellation details', async () => {
    const data = await client.request(gql`{
      clinicAppointments(where: { id: { eq: 17 } }) {
        status cancelled_at cancellation_reason
      }
    }`);
    expect(data.clinicAppointments[0].status).toBe('CANCELLED');
    expect(data.clinicAppointments[0].cancellation_reason).toBeTruthy();
  });
});

// ─── GraphQL: Prescriptions & Lab Results ────────────────────────────────────

describe('Clinic GraphQL — Prescriptions & Labs', () => {
  test('prescription with 3 FKs (appointment, patient, medication)', async () => {
    const data = await client.request(gql`{
      clinicPrescriptions(where: { id: { eq: 1 } }) {
        dosage frequency
        clinicAppointmentId { scheduled_at }
        clinicPatientId { name }
        clinicMedicationId { name dosage_form }
      }
    }`);
    const rx = data.clinicPrescriptions[0];
    expect(rx.clinicPatientId.name).toBe('John Doe');
    expect(rx.clinicMedicationId.name).toBe('Aspirin');
  });

  test('lab results with nullable appointment FK', async () => {
    const data = await client.request(gql`{
      clinicLabResults(where: { appointment_id: { isNull: true } }) {
        id value result_status notes
        clinicPatientId { name }
        clinicTestId { name unit }
      }
    }`);
    expect(data.clinicLabResults.length).toBeGreaterThanOrEqual(5);
    expect(data.clinicLabResults[0].notes).toContain('Routine');
  });

  test('filter by lab result status: critical', async () => {
    const data = await client.request(gql`{
      clinicLabResults(where: { result_status: { eq: "critical" } }) {
        value clinicPatientId { name } clinicTestId { name }
      }
    }`);
    expect(data.clinicLabResults.length).toBeGreaterThanOrEqual(3);
  });

  test('aggregate: lab result count', async () => {
    const data = await client.request(gql`{
      clinicLabResultsAggregate { count }
    }`);
    expect(data.clinicLabResultsAggregate.count).toBeGreaterThanOrEqual(25);
  });
});

// ─── GraphQL: Views ──────────────────────────────────────────────────────────

describe('Clinic GraphQL — Views', () => {
  test('upcoming_appointments view', async () => {
    const data = await client.request(gql`{
      clinicUpcomingAppointments { id patient_name doctor_name specialization }
    }`);
    expect(data.clinicUpcomingAppointments.length).toBeGreaterThanOrEqual(1);
  });

  test('patient_history view with aggregates', async () => {
    const data = await client.request(gql`{
      clinicPatientHistory { name appointment_count last_visit prescription_count }
    }`);
    expect(data.clinicPatientHistory.length).toBe(10);
    const john = data.clinicPatientHistory.find(p => p.name === 'John Doe');
    expect(Number(john.appointment_count)).toBeGreaterThanOrEqual(2);
  });

  test('connection pagination on appointments', async () => {
    const data = await client.request(gql`{
      clinicAppointmentsConnection(first: 5) {
        edges { node { id status } cursor }
        pageInfo { hasNextPage }
        totalCount
      }
    }`);
    expect(data.clinicAppointmentsConnection.edges).toHaveLength(5);
    expect(data.clinicAppointmentsConnection.pageInfo.hasNextPage).toBe(true);
    expect(data.clinicAppointmentsConnection.totalCount).toBeGreaterThanOrEqual(20);
  });
});

// ─── REST API ────────────────────────────────────────────────────────────────

describe('Clinic REST — Read', () => {
  test('GET /patients with allergies array', async () => {
    const res = await restGet('/patients?select=id,name,allergies');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBe(10);
  });

  test('GET /lab_results critical results', async () => {
    const res = await restGet('/lab_results?result_status=eq.critical&order=performed_at.desc');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(3);
  });

  test('GET /upcoming_appointments view', async () => {
    const res = await restGet('/upcoming_appointments');
    expect(res.status).toBe(200);
  });

  test('GET /patient_history view', async () => {
    const res = await restGet('/patient_history');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBe(10);
  });

  test('GET /lab_results CSV export', async () => {
    const res = await fetch(`${REST_URL}/lab_results?select=id,value,result_status&limit=5`, {
      headers: { 'Accept': 'text/csv', 'Accept-Profile': 'clinic', Authorization: `Bearer ${token}` },
    });
    expect(res.status).toBe(200);
    const csv = await res.text();
    expect(csv).toContain('id,value,result_status');
  });

  test('GET /appointments with Prefer: count=exact', async () => {
    const res = await restGet('/appointments', { 'Prefer': 'count=exact' });
    expect(res.status).toBe(200);
    expect(res.data.pagination.total).toBeGreaterThanOrEqual(20);
  });
});

describe('Clinic REST — Mutations', () => {
  let newAptId;

  test('POST /appointments schedules appointment', async () => {
    const res = await restPost('/appointments', {
      patient_id: 2, doctor_id: 4, scheduled_at: '2026-05-01T14:00:00Z',
      duration_minutes: 30, status: 'scheduled', notes: 'E2E test appointment',
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
    newAptId = res.data.data.id;
  });

  test('PATCH /appointments cancels appointment', async () => {
    const res = await restPatch(`/appointments?id=eq.${newAptId}`, {
      status: 'cancelled', cancellation_reason: 'E2E test cleanup',
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).toBe('cancelled');
  });

  test('POST /prescriptions creates prescription', async () => {
    const res = await restPost('/prescriptions', {
      appointment_id: 1, patient_id: 1, medication_id: 9,
      dosage: '500mg', frequency: 'As needed', duration_days: 14,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
  });

  test('POST /lab_results records result', async () => {
    const res = await restPost('/lab_results', {
      patient_id: 2, test_id: 1, value: 95,
      result_status: 'normal', notes: 'E2E test result',
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
  });

  test('POST /appointments with tx=rollback', async () => {
    const res = await restPost('/appointments', {
      patient_id: 1, doctor_id: 1, scheduled_at: '2026-06-01T09:00:00Z',
      duration_minutes: 30, notes: 'Should not persist',
    }, { 'Prefer': 'return=representation, tx=rollback' });
    expect(res.status).toBe(201);
    const check = await restGet(`/appointments?notes=eq.Should not persist`);
    expect(check.data.data).toHaveLength(0);
  });
});
