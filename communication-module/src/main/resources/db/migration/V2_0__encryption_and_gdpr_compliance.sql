-- Database migration for OpenMRS Communication Module
-- Encryption and GDPR compliance enhancement
-- Version: 2.0.0

-- ======== APPOINTMENT NOTIFICATION ENHANCEMENTS ========

-- Add encrypted fields to appointment_notification table
ALTER TABLE appointment_notification
ADD COLUMN patient_contact_encrypted TEXT;

ALTER TABLE appointment_notification
ADD COLUMN patient_name_encrypted TEXT;

ALTER TABLE appointment_notification
ADD COLUMN appointment_details_encrypted TEXT;

-- Convert existing appointment_start (Instant) to appointment_start_utc (LocalDateTime)
ALTER TABLE appointment_notification
ADD COLUMN appointment_start_utc TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Migrate data from old appointmentStart to new field (if exists)
UPDATE appointment_notification 
SET appointment_start_utc = appointment_start AT TIME ZONE 'UTC'
WHERE appointment_start IS NOT NULL AND appointment_start_utc IS NULL;

-- Add cancellation support
ALTER TABLE appointment_notification
ADD COLUMN cancellation_token VARCHAR(255) UNIQUE DEFAULT gen_random_uuid()::text;

ALTER TABLE appointment_notification
ADD COLUMN cancelled_by_patient BOOLEAN DEFAULT FALSE;

ALTER TABLE appointment_notification
ADD COLUMN cancelled_at TIMESTAMP;

-- Add provider tracking
ALTER TABLE appointment_notification
ADD COLUMN provider_type VARCHAR(50);

ALTER TABLE appointment_notification
ADD COLUMN provider_message_id VARCHAR(255);

ALTER TABLE appointment_notification
ADD COLUMN retry_count INTEGER DEFAULT 0;

ALTER TABLE appointment_notification
ADD COLUMN last_error TEXT;

-- Create indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_appt_cancellation_token 
  ON appointment_notification(cancellation_token);

CREATE INDEX IF NOT EXISTS idx_appt_provider_message_id 
  ON appointment_notification(provider_message_id);

CREATE INDEX IF NOT EXISTS idx_appt_cancelled 
  ON appointment_notification(cancelled_by_patient);

CREATE INDEX IF NOT EXISTS idx_appt_expires_purge
  ON appointment_notification(expires_at)
  WHERE patient_contact_encrypted IS NOT NULL;

-- ======== MESSAGE LOG ENHANCEMENTS ========

-- Add provider_message_ref for tracking with providers
ALTER TABLE message_log
ADD COLUMN provider_message_ref VARCHAR(255);

ALTER TABLE message_log
ADD COLUMN retry_count INTEGER DEFAULT 0;

-- Create index for efficient retention purging
CREATE INDEX IF NOT EXISTS idx_msg_log_expires
  ON message_log(expires_at);

CREATE INDEX IF NOT EXISTS idx_msg_log_org_status
  ON message_log(organisation_id, status);

-- ======== ORGANISATION CONFIG ENHANCEMENTS ========

-- Add encryption key path field
ALTER TABLE organisation_config
ADD COLUMN vault_credentials_path VARCHAR(255);

-- Create index on active organisations
CREATE INDEX IF NOT EXISTS idx_org_active
  ON organisation_config(active);

-- ======== ADD AUDIT LOG TABLE ========

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action VARCHAR(255) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    organisation_id UUID,
    actor VARCHAR(255),
    description TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    details TEXT
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_organisation ON audit_log(organisation_id);

-- ======== PROVIDER CREDENTIALS TABLE ========
-- Stores encrypted provider-specific credentials in database
-- Keys are stored in Vault and referenced here

CREATE TABLE IF NOT EXISTS provider_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organisation_id UUID NOT NULL,
    provider_type VARCHAR(50) NOT NULL,
    credentials_encrypted TEXT NOT NULL,
    vault_path VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organisation_id) REFERENCES organisation_config(id) ON DELETE CASCADE,
    UNIQUE(organisation_id, provider_type)
);

CREATE INDEX idx_provider_creds_org ON provider_credentials(organisation_id);

-- ======== NOTIFICATIONS_TO_SEND QUEUE ========
-- Holds notifications awaiting processing by provider connectors

CREATE TABLE IF NOT EXISTS notifications_to_send (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_notification_id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    provider_type VARCHAR(50) NOT NULL,
    message_content_encrypted TEXT NOT NULL,
    cancellation_token VARCHAR(255),
    status VARCHAR(50) DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    next_retry_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP + INTERVAL '14 days',
    FOREIGN KEY (appointment_notification_id) REFERENCES appointment_notification(id) ON DELETE CASCADE,
    FOREIGN KEY (organisation_id) REFERENCES organisation_config(id) ON DELETE CASCADE
);

CREATE INDEX idx_notify_status ON notifications_to_send(status);
CREATE INDEX idx_notify_retry ON notifications_to_send(next_retry_at) WHERE status = 'RETRYING';
CREATE INDEX idx_notify_expires ON notifications_to_send(expires_at);

-- ======== VIEWS FOR MONITORING ========

-- View: Pending notifications ready to send
CREATE OR REPLACE VIEW pending_notifications_24h AS
SELECT 
    n.id,
    n.organisation_id,
    n.fhir_appointment_id,
    n.notify_at_24h,
    n.sent_24h,
    o.provider_type
FROM appointment_notification n
JOIN organisation_config o ON n.organisation_id = o.id
WHERE NOT n.sent_24h 
  AND n.notify_at_24h <= CURRENT_TIMESTAMP
  AND n.status = 'PENDING'
  AND o.active = TRUE;

CREATE OR REPLACE VIEW pending_notifications_1h AS
SELECT 
    n.id,
    n.organisation_id,
    n.fhir_appointment_id,
    n.notify_at_1h,
    n.sent_1h,
    o.provider_type
FROM appointment_notification n
JOIN organisation_config o ON n.organisation_id = o.id
WHERE NOT n.sent_1h 
  AND n.notify_at_1h <= CURRENT_TIMESTAMP
  AND n.status = 'PENDING'
  AND o.active = TRUE;

-- View: Notifications awaiting purge (14-day retention expired)
CREATE OR REPLACE VIEW notifications_ready_for_purge AS
SELECT 
    id,
    fhir_appointment_id,
    organisation_id,
    expires_at
FROM appointment_notification
WHERE expires_at <= CURRENT_TIMESTAMP
  AND (patient_contact_encrypted IS NOT NULL 
       OR patient_name_encrypted IS NOT NULL 
       OR appointment_details_encrypted IS NOT NULL);

-- View: Message logs ready for deletion (1-year retention expired)
CREATE OR REPLACE VIEW message_logs_ready_for_deletion AS
SELECT 
    id,
    organisation_id,
    expires_at
FROM message_log
WHERE expires_at <= CURRENT_TIMESTAMP;

-- ======== DATA MIGRATION ========

-- For existing data: populate vault_credentials_path if not already set
UPDATE organisation_config
SET vault_credentials_path = CONCAT('secret/communicatiemodule/organisations/', id::text)
WHERE vault_credentials_path IS NULL;

-- COMMIT
COMMIT;
