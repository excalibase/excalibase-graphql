CREATE SCHEMA clinic;
GRANT USAGE ON SCHEMA clinic TO app_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA clinic TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA clinic GRANT ALL PRIVILEGES ON TABLES TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA clinic TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA clinic GRANT USAGE, SELECT ON SEQUENCES TO app_user;

CREATE TYPE clinic.gender AS ENUM ('male', 'female', 'other');
CREATE TYPE clinic.appointment_status AS ENUM ('scheduled', 'confirmed', 'in_progress', 'completed', 'cancelled', 'no_show');
CREATE TYPE clinic.severity AS ENUM ('mild', 'moderate', 'severe');
CREATE TYPE clinic.lab_status AS ENUM ('normal', 'abnormal', 'critical');

CREATE TABLE clinic.specializations (
    id SERIAL PRIMARY KEY,
    name TEXT UNIQUE NOT NULL
);

CREATE TABLE clinic.doctors (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    license_number TEXT UNIQUE NOT NULL,
    specialization_id INTEGER NOT NULL REFERENCES clinic.specializations(id),
    active BOOLEAN DEFAULT true
);

CREATE TABLE clinic.doctor_availability (
    id SERIAL PRIMARY KEY,
    doctor_id INTEGER NOT NULL REFERENCES clinic.doctors(id),
    day_of_week INTEGER NOT NULL CHECK (day_of_week >= 0 AND day_of_week <= 6),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CHECK (start_time < end_time)
);

CREATE TABLE clinic.patients (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    date_of_birth DATE NOT NULL,
    gender clinic.gender NOT NULL,
    phone TEXT,
    email TEXT,
    emergency_contact JSONB,
    blood_type TEXT,
    allergies TEXT[] DEFAULT '{}'
);

CREATE TABLE clinic.appointments (
    id SERIAL PRIMARY KEY,
    patient_id INTEGER NOT NULL REFERENCES clinic.patients(id),
    doctor_id INTEGER NOT NULL REFERENCES clinic.doctors(id),
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 30 CHECK (duration_minutes > 0),
    status clinic.appointment_status DEFAULT 'scheduled',
    notes TEXT,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT
);

CREATE TABLE clinic.diagnoses (
    id SERIAL PRIMARY KEY,
    appointment_id INTEGER NOT NULL REFERENCES clinic.appointments(id),
    icd_code TEXT NOT NULL,
    description TEXT NOT NULL,
    severity clinic.severity DEFAULT 'mild'
);

CREATE TABLE clinic.medications (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    dosage_form TEXT,
    manufacturer TEXT
);

CREATE TABLE clinic.prescriptions (
    id SERIAL PRIMARY KEY,
    appointment_id INTEGER NOT NULL REFERENCES clinic.appointments(id),
    patient_id INTEGER NOT NULL REFERENCES clinic.patients(id),
    medication_id INTEGER NOT NULL REFERENCES clinic.medications(id),
    dosage TEXT NOT NULL,
    frequency TEXT NOT NULL,
    duration_days INTEGER,
    refills_remaining INTEGER DEFAULT 0,
    prescribed_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE clinic.lab_tests (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    unit TEXT,
    normal_range_low NUMERIC,
    normal_range_high NUMERIC
);

CREATE TABLE clinic.lab_results (
    id SERIAL PRIMARY KEY,
    appointment_id INTEGER REFERENCES clinic.appointments(id),
    patient_id INTEGER NOT NULL REFERENCES clinic.patients(id),
    test_id INTEGER NOT NULL REFERENCES clinic.lab_tests(id),
    value NUMERIC NOT NULL,
    result_status clinic.lab_status DEFAULT 'normal',
    performed_at TIMESTAMPTZ DEFAULT now(),
    notes TEXT
);

CREATE VIEW clinic.upcoming_appointments AS
    SELECT a.id, a.scheduled_at, a.status, a.duration_minutes,
           p.name AS patient_name, d.name AS doctor_name, s.name AS specialization
    FROM clinic.appointments a
    JOIN clinic.patients p ON p.id = a.patient_id
    JOIN clinic.doctors d ON d.id = a.doctor_id
    JOIN clinic.specializations s ON s.id = d.specialization_id
    WHERE a.status IN ('scheduled', 'confirmed');

CREATE VIEW clinic.patient_history AS
    SELECT p.id, p.name, p.date_of_birth,
           COUNT(DISTINCT a.id) AS appointment_count,
           MAX(a.scheduled_at) AS last_visit,
           COUNT(DISTINCT pr.id) AS prescription_count
    FROM clinic.patients p
    LEFT JOIN clinic.appointments a ON a.patient_id = p.id
    LEFT JOIN clinic.prescriptions pr ON pr.patient_id = p.id
    GROUP BY p.id, p.name, p.date_of_birth;

CREATE OR REPLACE FUNCTION clinic.patient_age(clinic.patients)
RETURNS INTEGER LANGUAGE sql STABLE AS $$
    SELECT EXTRACT(YEAR FROM age(now(), $1.date_of_birth))::integer;
$$;

-- Seed data
INSERT INTO clinic.specializations (name) VALUES
    ('Cardiology'), ('Orthopedics'), ('Dermatology'), ('General Practice');

INSERT INTO clinic.doctors (name, email, license_number, specialization_id) VALUES
    ('Dr. Sarah Kim', 'sarah@clinic.com', 'MD-001', 1),
    ('Dr. James Chen', 'james@clinic.com', 'MD-002', 2),
    ('Dr. Maria Garcia', 'maria@clinic.com', 'MD-003', 3),
    ('Dr. Robert Taylor', 'robert@clinic.com', 'MD-004', 4),
    ('Dr. Lisa Wong', 'lisa@clinic.com', 'MD-005', 1),
    ('Dr. Ahmed Hassan', 'ahmed@clinic.com', 'MD-006', 4);

INSERT INTO clinic.doctor_availability (doctor_id, day_of_week, start_time, end_time) VALUES
    (1, 1, '09:00', '17:00'), (1, 2, '09:00', '17:00'), (1, 3, '09:00', '13:00'),
    (2, 1, '08:00', '16:00'), (2, 3, '08:00', '16:00'), (2, 5, '08:00', '12:00'),
    (3, 2, '10:00', '18:00'), (3, 4, '10:00', '18:00'),
    (4, 1, '08:00', '17:00'), (4, 2, '08:00', '17:00'), (4, 3, '08:00', '17:00'),
    (6, 4, '09:00', '15:00');

INSERT INTO clinic.patients (name, date_of_birth, gender, phone, email, emergency_contact, blood_type, allergies) VALUES
    ('John Doe', '1985-03-15', 'male', '555-0201', 'john@email.com', '{"name":"Jane Doe","phone":"555-0202","relation":"spouse"}', 'O+', '{penicillin}'),
    ('Emma Wilson', '1990-07-22', 'female', '555-0203', 'emma@email.com', '{"name":"Tom Wilson","phone":"555-0204","relation":"father"}', 'A+', '{}'),
    ('Michael Brown', '1978-11-08', 'male', '555-0205', 'michael@email.com', NULL, 'B+', '{aspirin,ibuprofen}'),
    ('Sophie Lee', '2000-01-30', 'female', '555-0207', 'sophie@email.com', '{"name":"Mom Lee","phone":"555-0208","relation":"mother"}', 'AB-', '{}'),
    ('David Martinez', '1965-09-14', 'male', '555-0209', 'david@email.com', '{"name":"Rosa Martinez","phone":"555-0210","relation":"wife"}', 'O-', '{sulfa}'),
    ('Olivia Taylor', '1995-05-18', 'female', '555-0211', NULL, NULL, 'A-', '{}'),
    ('James Anderson', '1988-12-03', 'male', '555-0213', 'james@email.com', '{"name":"Lisa Anderson","phone":"555-0214","relation":"sister"}', 'B-', '{latex}'),
    ('Ava Thomas', '2010-04-25', 'female', '555-0215', NULL, '{"name":"Mark Thomas","phone":"555-0216","relation":"father"}', 'O+', '{}'),
    ('William Jackson', '1972-08-19', 'male', '555-0217', 'william@email.com', NULL, 'AB+', '{penicillin,codeine}'),
    ('Mia White', '1998-02-11', 'female', '555-0219', 'mia@email.com', '{"name":"Dan White","phone":"555-0220","relation":"brother"}', 'A+', '{}');

INSERT INTO clinic.appointments (patient_id, doctor_id, scheduled_at, duration_minutes, status, notes) VALUES
    (1, 1, '2026-04-01 09:00', 30, 'completed', 'Annual checkup'),
    (1, 1, '2026-04-15 10:00', 45, 'completed', 'Follow-up ECG'),
    (2, 4, '2026-04-02 11:00', 30, 'completed', 'Flu symptoms'),
    (3, 2, '2026-04-03 14:00', 60, 'completed', 'Knee pain evaluation'),
    (4, 3, '2026-04-05 10:00', 30, 'completed', 'Skin rash'),
    (5, 1, '2026-04-07 09:00', 45, 'completed', 'Chest pain evaluation'),
    (5, 1, '2026-04-14 09:00', 30, 'completed', 'Stress test results'),
    (6, 4, '2026-04-08 15:00', 30, 'completed', 'Annual physical'),
    (7, 2, '2026-04-09 08:00', 45, 'completed', 'Sports injury'),
    (8, 4, '2026-04-10 11:00', 30, 'completed', 'Vaccination'),
    (9, 1, '2026-04-11 09:00', 60, 'completed', 'Heart murmur evaluation'),
    (10, 3, '2026-04-12 14:00', 30, 'completed', 'Acne treatment'),
    (1, 5, '2026-04-20 10:00', 30, 'scheduled', 'Cardio follow-up'),
    (3, 2, '2026-04-21 14:00', 45, 'confirmed', 'MRI review'),
    (5, 1, '2026-04-22 09:00', 30, 'scheduled', 'Monthly checkup'),
    (2, 6, '2026-04-23 09:00', 30, 'scheduled', 'General checkup'),
    (7, 2, '2026-04-10 10:00', 30, 'cancelled', 'Patient requested reschedule'),
    (4, 3, '2026-04-25 10:00', 30, 'scheduled', 'Follow-up'),
    (9, 5, '2026-04-26 11:00', 45, 'scheduled', 'Echo test'),
    (6, 4, '2026-04-28 15:00', 30, 'scheduled', 'Blood work review');

UPDATE clinic.appointments SET cancelled_at = '2026-04-09', cancellation_reason = 'Schedule conflict' WHERE id = 17;

INSERT INTO clinic.diagnoses (appointment_id, icd_code, description, severity) VALUES
    (1, 'Z00.00', 'General adult medical exam', 'mild'),
    (2, 'I25.10', 'Atherosclerotic heart disease', 'moderate'),
    (3, 'J06.9', 'Acute upper respiratory infection', 'mild'),
    (4, 'M17.11', 'Primary osteoarthritis right knee', 'moderate'),
    (5, 'L30.9', 'Dermatitis unspecified', 'mild'),
    (6, 'R07.9', 'Chest pain unspecified', 'severe'),
    (7, 'I25.10', 'Atherosclerotic heart disease', 'moderate'),
    (9, 'S83.511', 'Sprain of ACL right knee', 'severe'),
    (11, 'R01.1', 'Cardiac murmur unspecified', 'moderate'),
    (12, 'L70.0', 'Acne vulgaris', 'mild'),
    (4, 'M25.561', 'Pain in right knee', 'moderate'),
    (6, 'I10', 'Essential hypertension', 'moderate'),
    (8, 'Z00.00', 'General adult medical exam', 'mild'),
    (2, 'I10', 'Essential hypertension', 'mild'),
    (11, 'I10', 'Essential hypertension', 'moderate');

INSERT INTO clinic.medications (name, dosage_form, manufacturer) VALUES
    ('Aspirin', 'Tablet', 'Bayer'),
    ('Lisinopril', 'Tablet', 'Merck'),
    ('Amoxicillin', 'Capsule', 'Pfizer'),
    ('Ibuprofen', 'Tablet', 'Advil'),
    ('Metformin', 'Tablet', 'Bristol-Myers'),
    ('Atorvastatin', 'Tablet', 'Pfizer'),
    ('Tretinoin', 'Cream', 'Galderma'),
    ('Prednisone', 'Tablet', 'Merck'),
    ('Acetaminophen', 'Tablet', 'Tylenol'),
    ('Omeprazole', 'Capsule', 'AstraZeneca');

INSERT INTO clinic.prescriptions (appointment_id, patient_id, medication_id, dosage, frequency, duration_days, refills_remaining) VALUES
    (1, 1, 1, '81mg', 'Daily', 365, 3),
    (2, 1, 2, '10mg', 'Daily', 90, 2),
    (2, 1, 6, '20mg', 'Daily', 90, 2),
    (3, 2, 3, '500mg', 'Three times daily', 10, 0),
    (4, 3, 4, '400mg', 'As needed', 30, 1),
    (5, 4, 8, '10mg', 'Daily for 7 days', 7, 0),
    (6, 5, 2, '20mg', 'Daily', 90, 3),
    (6, 5, 1, '325mg', 'Daily', 365, 3),
    (7, 5, 6, '40mg', 'Daily', 90, 2),
    (8, 6, 10, '20mg', 'Daily', 30, 1),
    (9, 7, 4, '600mg', 'Three times daily', 14, 0),
    (9, 7, 9, '500mg', 'As needed', 30, 1),
    (11, 9, 2, '10mg', 'Daily', 90, 2),
    (11, 9, 6, '10mg', 'Daily', 90, 2),
    (12, 10, 7, '0.025%', 'Nightly', 90, 2),
    (4, 3, 9, '500mg', 'As needed', 30, 2),
    (6, 5, 10, '40mg', 'Daily', 30, 1),
    (2, 1, 10, '20mg', 'Daily', 30, 1);

INSERT INTO clinic.lab_tests (name, unit, normal_range_low, normal_range_high) VALUES
    ('Blood Glucose', 'mg/dL', 70, 100),
    ('Hemoglobin', 'g/dL', 12.0, 17.5),
    ('Total Cholesterol', 'mg/dL', 0, 200),
    ('LDL Cholesterol', 'mg/dL', 0, 100),
    ('HDL Cholesterol', 'mg/dL', 40, 60),
    ('Triglycerides', 'mg/dL', 0, 150),
    ('Blood Pressure Sys', 'mmHg', 90, 120),
    ('Blood Pressure Dia', 'mmHg', 60, 80),
    ('Heart Rate', 'bpm', 60, 100),
    ('Creatinine', 'mg/dL', 0.7, 1.3);

INSERT INTO clinic.lab_results (appointment_id, patient_id, test_id, value, result_status, notes) VALUES
    (1, 1, 1, 92, 'normal', NULL),
    (1, 1, 3, 215, 'abnormal', 'Slightly elevated'),
    (1, 1, 7, 135, 'abnormal', 'Stage 1 hypertension'),
    (2, 1, 3, 198, 'normal', 'Improved with medication'),
    (2, 1, 4, 110, 'abnormal', 'Borderline high'),
    (2, 1, 9, 72, 'normal', NULL),
    (3, 2, 1, 88, 'normal', NULL),
    (3, 2, 2, 13.5, 'normal', NULL),
    (6, 5, 1, 145, 'abnormal', 'Pre-diabetic range'),
    (6, 5, 3, 260, 'abnormal', 'High cholesterol'),
    (6, 5, 7, 155, 'critical', 'Stage 2 hypertension'),
    (6, 5, 8, 95, 'critical', 'Stage 2 hypertension'),
    (7, 5, 1, 130, 'abnormal', 'Still elevated'),
    (7, 5, 7, 140, 'abnormal', 'Improved slightly'),
    (8, 6, 1, 85, 'normal', NULL),
    (8, 6, 2, 14.2, 'normal', NULL),
    (11, 9, 3, 240, 'abnormal', 'High cholesterol'),
    (11, 9, 9, 88, 'normal', NULL),
    (11, 9, 7, 145, 'abnormal', 'Hypertension'),
    (NULL, 3, 1, 95, 'normal', 'Routine lab, no appointment'),
    (NULL, 3, 2, 15.1, 'normal', 'Routine lab'),
    (NULL, 7, 10, 1.8, 'abnormal', 'Elevated creatinine'),
    (NULL, 10, 1, 78, 'normal', 'Routine screening'),
    (NULL, 4, 2, 13.0, 'normal', 'Pre-surgery labs'),
    (NULL, 5, 1, 155, 'critical', 'Urgent follow-up needed');
