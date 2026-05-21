# OpenMRS Communication Module - Complete Integration Guide

## 🏥 Overview

A **SaaS healthcare notification service** that integrates with OpenMRS to send automated appointment reminders to patients via their preferred communication channel (SMS, Email, WhatsApp, etc.).

### Key Features

✅ **Hospital Provider Selection**: Each hospital chooses their preferred communication provider at setup
✅ **End-to-End Encryption**: AES-256-GCM for data at rest, TLS 1.3 for transport
✅ **FHIR R4 Compliance**: Full HL7 FHIR R4 message processing (receipt, validation, ACK, transformation, queueing, retry)
✅ **Patient Appointment Cancellation**: Patients can cancel appointments via secure link in notification
✅ **GDPR Compliance**: Automatic PII purging (14 days), audit retention (1 year)
✅ **Multi-Timezone Support**: Notifications sent in patient's local timezone
✅ **Resilient Message Processing**: Exponential backoff retry, provider fallback, dead letter queue
✅ **Multi-Charset Support**: UTF-8 and international character support
✅ **Independent Operation**: Works autonomously; provider downtime handled gracefully

---

## 🏗️ System Architecture

```
OpenMRS Instance (Hospital)
    ↓
    ├─ FHIR Appointment Created
    ├─ REST Subscription Webhook Callback
    ↓
Communication Module (/fhir/webhook/{organisationId})
    ├─ Validate Bearer Token
    ├─ Parse FHIR Resource
    ├─ Extract & Encrypt Patient Data (AES-256)
    ├─ Store in PostgreSQL
    ↓
Notification Scheduler (background job)
    ├─ Poll for appointments 24h before
    ├─ Poll for appointments 1h before
    ├─ Queue notifications to RabbitMQ
    ↓
Message Consumer (background job)
    ├─ Dequeue notification
    ├─ Decrypt patient data
    ├─ Generate notification message
    ├─ Send via selected provider (SwiftSend, SecurePost, etc.)
    ├─ On failure: Retry with exponential backoff
    ├─ Log delivery status
    ↓
Patient
    ├─ Receives SMS/Email/WhatsApp notification
    ├─ Reads appointment details + cancellation link
    ├─ Optionally clicks link to cancel
    ↓
Appointment Cancellation Handler
    ├─ Validate cancellation token
    ├─ Update appointment in OpenMRS via FHIR API
    ├─ Confirm cancellation to patient
    ↓
Data Retention Jobs (automated)
    ├─ 14 days: Purge patient contact & appointment details
    ├─ 1 year: Delete audit logs (keep delivery records for compliance)
```

---

## 🚀 Hospital Setup Flow

### Step 1: Hospital Registers

**Endpoint**: `POST /api/organisations`

```bash
curl -u admin:admin -X POST http://localhost:8080/api/organisations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Ziekenhuis Utrecht",
    "openmrsBaseUrl": "https://openmrs.ziekenhuis-utrecht.nl",
    "providerType": "SWIFT_SEND",
    "callbackToken": "unique-secure-token-xyz",
    "timezone": "Europe/Amsterdam",
    "language": "nl"
  }'
```

**Response**: Organisation UUID + encryption keys generated in Vault

### Step 2: Hospital Changes Provider Preference

**Endpoint**: `PUT /api/organisations/{organisationId}/provider`

```bash
curl -u admin:admin -X PUT http://localhost:8080/api/organisations/{organisationId}/provider \
  -H "Content-Type: application/json" \
  -d '{
    "providerType": "SECURE_POST"
  }'
```

### Step 3: Hospital Configures OpenMRS Subscription

In OpenMRS FHIR configuration:

```json
{
  "resourceType": "Subscription",
  "criteria": "Appointment?status=booked",
  "channel": {
    "type": "rest-hook",
    "endpoint": "http://communicatiemodule:8080/fhir/webhook/{organisationId}",
    "header": ["Authorization: Bearer unique-secure-token-xyz"]
  }
}
```

Now, whenever an appointment is created in OpenMRS, the Communication Module receives a FHIR webhook.

---

## 🔐 Encryption & Security

### Data Encryption

**At Rest** (Database):

- Algorithm: **AES-256-GCM** (authenticated encryption mode)
- Each encrypted field has unique IV/nonce (prevents pattern matching)
- Key Format: `BASE64(IV[96-bit] + CIPHERTEXT + TAG[128-bit])`
- Keys stored in **Vault**, never in database

**Fields Encrypted**:

```
- patientContactEncrypted      (phone/email)
- patientNameEncrypted          (patient name)
- appointmentDetailsEncrypted   (date/time/location/instructions)
```

**In Transit** (Network):

- **TLS 1.3** enforced at reverse proxy / load balancer
- All FHIR webhooks must use HTTPS
- Bearer token authentication

### Key Management (Vault)

Each organisation gets:

- Master key at: `secret/communicatiemodule/organisations/{orgId}/master-key`
- Key rotated every 90 days (configurable)
- Automatic key derivation for field-level encryption

### Example: Encryption Flow

```
Plain Patient Contact: "+31612345678"
                ↓
Encrypt with org's master key (AES-256-GCM)
                ↓
BASE64(IV + CIPHERTEXT + TAG)
= "aGVsbG8gd29ybGRoZWxsbyB3b3JsZGhlbGxvIHdvcmxkCg=="
                ↓
Store in DB as patientContactEncrypted
```

---

## 📋 Message Processing Pipeline (FHIR HL7)

### Phase 1: Receipt

```
POST /fhir/webhook/{organisationId}
Authorization: Bearer {callbackToken}
Content-Type: application/fhir+json

{FHIR Appointment Resource}
    ↓
Parse & validate Bearer token
    ↓
Return HTTP 200 (ACK) immediately
```

### Phase 2: Validation

```
Extract appointment fields:
- appointmentStart (date/time)
- participantLocation (location)
- participantActor (patient, location)
- comment (instructions)

Validate required fields:
- ✓ Start time is in future
- ✓ Status is "booked" (not cancelled)
- ✓ Location exists
- ✓ Patient contact exists (phone/email)

If validation fails: Log error, skip notification
```

### Phase 3: Encryption & Storage

```
Extract patient PII:
- Name: "Jan de Vries"
- Contact: "+31612345678"
- Appointment details: "Polikliniek B, 15 juni, 10:00-10:30, Nuchter komen"

Encrypt each field with AES-256-GCM
Generate unique cancellation token (UUID)

Store in AppointmentNotification:
- fhirAppointmentId
- patientContactEncrypted
- patientNameEncrypted
- appointmentDetailsEncrypted
- cancellationToken
- appointmentStartUtc
- notifyAt24h, notifyAt1h
- organisationId
- status = PENDING
```

### Phase 4: Scheduling

```
Background job (runs every 30 seconds):
  For each PENDING appointment:
    If NOW >= notifyAt24h && !sent24h:
      Queue message with reminderType="REMINDER_24H"
    If NOW >= notifyAt1h && !sent1h:
      Queue message with reminderType="REMINDER_1H"
```

### Phase 5: Transformation & Queueing

```
For each queued notification:
  1. Decrypt appointment details (using Vault key)
  2. Format message for target language/timezone
  3. Add cancellation link: https://service/api/appointments/cancel/{orgId}/{token}
  4. Create NotificationMessage object (with encrypted fields)
  5. Queue to RabbitMQ: notification.queue
```

### Phase 6: Provider Integration

```
Consumer listens on notification.queue:
  1. Dequeue message
  2. Decrypt patient contact
  3. Get provider credentials from Vault
  4. Call provider API (SwiftSend, SecurePost, etc.)
  5. Handle response:
     - Success: Log MessageLog with status=SENT
     - Failure: Increment retryCount, requeue with exponential backoff
     - Max retries exceeded: Set status=FAILED, alert admin
```

### Phase 7: Retry with Exponential Backoff

```
Retry schedule:
  Attempt 1: 2 seconds
  Attempt 2: 4 seconds
  Attempt 3: 8 seconds
  Attempt 4: 16 seconds
  Attempt 5: 32 seconds (max 1 hour)

+ Random jitter (0-10 seconds) to prevent thundering herd
```

---

## 🔗 Patient Appointment Cancellation

### Cancellation Link in Notification

```
Subject: Afspraak herinnering - Ziekenhuis Utrecht

Beste Jan,

Uw afspraak is gepland op:
📅 15 juni 2025, 10:00-10:30 uur
📍 Polikliniek B, Kamer 12
📋 Nuchter komen

Wilt u deze afspraak annuleren?
👉 ANNULEREN: https://communicatiemodule.example.com/api/appointments/cancel/
   {organisationId}/{cancellationToken}
```

### Cancellation Flow

```
1. Patient clicks link (or GET request)
   GET /api/appointments/cancel/{organisationId}/{cancellationToken}
        ↓
2. Validate cancellation token is:
   - Valid UUID format
   - Exists in database
   - Belongs to specified organisation
   - Not already used
   - Not expired (within 14 days of appointment)
        ↓
3. Update appointment in OpenMRS via FHIR API:
   PUT /fhir/Appointment/{fhirId}
   { "status": "cancelled" }
        ↓
4. Update local record:
   - Set cancelledByPatient = true
   - Set cancelledAt = now()
        ↓
5. Return confirmation to patient:
   ✓ "Afspraak geannuleerd - uw arts is hiervan op de hoogte"
```

---

## ⏰ Multi-Timezone Support

### Timezone-Aware Notification Timing

```
Hospital configured timezone: "Europe/Amsterdam"

FHIR Appointment (UTC):
  start: "2025-06-15T08:00:00Z"  (UTC)

Convert to hospital timezone:
  start: "2025-06-15T10:00:00+02:00"  (Amsterdam = UTC+2)

Calculate notification times:
  24h reminder: "2025-06-14T10:00:00+02:00"
  1h reminder:  "2025-06-15T09:00:00+02:00"

Format for patient (Dutch):
  "15 juni 2025, 10:00-10:30 uur"
```

### Multi-Charset Support

```
✓ UTF-8 (default)
✓ Latin Extended (é, ñ, ü, etc.)
✓ Chinese, Arabic, Japanese, etc.

Database:
  encoding: UTF-8
  collation: en_US.UTF-8

Validation:
  Before encryption, normalize Unicode (NFC)
  Validate character encoding
  Log encoding warnings for debugging
```

---

## 🛡️ GDPR & Data Retention

### Automatic Data Purging

**Patient Data (14-Day Retention)**

```
Scheduled Job (Daily at 2 AM):
  SELECT * FROM appointment_notification
  WHERE expires_at <= NOW()

  FOR EACH record:
    - SET patientContactEncrypted = NULL
    - SET patientNameEncrypted = NULL
    - SET appointmentDetailsEncrypted = NULL
    - KEEP: appointmentStartUtc, cancellationToken, status, timestamps
```

Why keep non-PII fields?

- Audit trail (who cancelled, when)
- Statistics (success rate, retry count)
- Legal compliance (prove notification was sent)

**Message Logs (1-Year Retention)**

```
Scheduled Job (Daily at 3 AM):
  DELETE FROM message_log
  WHERE expires_at <= NOW()

  Reason: After 1 year, audit trail no longer needed
  - Provider billing reconciliation complete
  - Legal disputes resolved
  - Compliance requirements met
```

### Compliance Endpoints

```
GET /api/compliance/retention-policy
  Returns: Full policy details, retention periods, compliance info

GET /api/compliance/security-policy
  Returns: Encryption algorithms, key management, TLS details

GET /api/compliance/hl7-compliance
  Returns: FHIR R4 standards compliance details
```

---

## 📊 Monitoring & Observability

### Metrics (Prometheus)

```
# Notification processing
notification_queue_size             (gauge)
notification_processing_time_ms     (histogram)
notifications_sent_total            (counter)
notifications_failed_total          (counter)
notifications_retried_total         (counter)

# Provider health
provider_response_time_ms           (histogram)
provider_success_rate               (gauge)
provider_downtime_minutes           (gauge)

# Data retention
encrypted_records_count             (gauge)
audit_logs_count                    (gauge)
```

### Health Check

```
GET /actuator/health
Response:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "rabbitmq": { "status": "UP" },
    "vault": { "status": "UP" }
  }
}
```

### Dashboard URLs

| Service         | URL                      | Credentials       |
| --------------- | ------------------------ | ----------------- |
| Application API | http://localhost:8080    | admin/admin       |
| Prometheus      | http://localhost:9090    | —                 |
| Grafana         | http://localhost:3000    | admin/admin       |
| RabbitMQ        | http://localhost:15672   | guest/guest       |
| Vault           | http://localhost:8200/ui | token: root-token |

---

## 🚨 Fallback & Resilience

### Provider Downtime Handling

```
If provider API returns 5xx error:
  1. Retry with exponential backoff
  2. After 5 retries, move to Dead Letter Queue
  3. Monitor and alert operations team
  4. (Optional) Switch to fallback provider

Fallback providers (if configured):
  Primary: SWIFT_SEND
  Secondary: ASYNC_FLOW
  Tertiary: SECURE_POST (slower but reliable)
```

### Dead Letter Queue

```
After max retries exceeded:
  Message moved to notification.dlq

  Notification:
  {
    "status": "FAILED",
    "reason": "Max retries exceeded",
    "lastError": "Connection timeout to provider",
    "retryCount": 5,
    "message": {
      "appointmentId": "...",
      "patientContact": "encrypted...",
      "timestamp": "..."
    }
  }

  Manual intervention:
  - Review error logs
  - Fix provider credentials if needed
  - Requeue notification manually via API
```

---

## 🔄 Available Communication Providers

### 1. **SwiftSend** - SMS/WhatsApp

- **Channels**: SMS, WhatsApp, MMS
- **Speed**: ⚡⚡⚡ Fast (< 1 second)
- **Reliability**: 99.5% uptime SLA
- **Cost**: €0.05-0.15 per message

### 2. **SecurePost** - Encrypted Mail

- **Channels**: Email, postal mail, encrypted delivery
- **Speed**: ⏱️ Slow (1-3 hours for postal)
- **Reliability**: 100% guarantee with tracking
- **Cost**: €0.30-2.00 per delivery
- **Use Case**: HIPAA/GDPR-sensitive data

### 3. **LegacyLink** - Old Systems

- **Channels**: Fax, phone, email
- **Speed**: ⏱️ Slow (varies by channel)
- **Reliability**: 95% (fax machine availability)
- **Cost**: €0.10-0.50 per message
- **Use Case**: Backward compatibility

### 4. **AsyncFlow** - Async Queue Processing

- **Channels**: Any (multi-channel)
- **Speed**: ⏱️⏱️ Moderate (1-5 seconds)
- **Reliability**: 99.9% with custom retry logic
- **Cost**: €0.08-0.20 per message
- **Use Case**: High volume, flexible

---

## 🧪 Testing & Integration

### Test FHIR Webhook

```bash
curl -X POST "http://localhost:8080/fhir/webhook/{organisationId}" \
  -H "Authorization: Bearer {callbackToken}" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Appointment",
    "id": "appt-test-001",
    "status": "booked",
    "start": "2025-06-15T10:00:00+02:00",
    "comment": "Test appointment",
    "participant": [
      {
        "actor": {
          "reference": "Patient/p-001",
          "display": "Test Patient"
        },
        "status": "accepted"
      },
      {
        "actor": {
          "reference": "Location/loc-001",
          "display": "Clinic Room 1"
        },
        "status": "accepted"
      }
    ],
    "extension": [
      {
        "url": "http://example.com/fhir/extension/patient-phone",
        "valueString": "+31612345678"
      }
    ]
  }'
```

### Test Cancellation Link

```bash
curl -X GET "http://localhost:8080/api/appointments/cancel/{organisationId}/{cancellationToken}"
```

### Database Verification

```sql
-- Check encrypted data is stored
SELECT id, fhir_appointment_id, patient_contact_encrypted, status
FROM appointment_notification
WHERE organisation_id = '{organisationId}'
LIMIT 5;

-- Check message logs
SELECT id, provider_type, status, sent_at, retry_count
FROM message_log
WHERE organisation_id = '{organisationId}'
ORDER BY created_at DESC
LIMIT 10;
```

---

## 🚀 Deployment Checklist

- [ ] **Database**: PostgreSQL running with migration V2.0 applied
- [ ] **Vault**: Vault running with root token configured
- [ ] **RabbitMQ**: RabbitMQ running with guest/guest credentials
- [ ] **OpenMRS**: FHIR Subscription configured → Communication Module webhook
- [ ] **SSL/TLS**: Reverse proxy enforcing TLS 1.3
- [ ] **Encryption Keys**: Master keys generated in Vault for each organisation
- [ ] **Provider APIs**: All provider credentials stored in Vault
- [ ] **Monitoring**: Prometheus scraping /actuator/prometheus
- [ ] **Alerts**: PagerDuty integration for failed notifications
- [ ] **Backup**: Daily automated PostgreSQL backup

---

## 📞 Support & Troubleshooting

### Logs

```bash
# Check app logs
docker logs communicatiemodule

# Check specific org
tail -f /var/log/communicatiemodule.log | grep "{organisationId}"

# Check encryption errors
tail -f /var/log/communicatiemodule.log | grep "encryption"
```

### Common Issues

**Issue**: "Invalid Bearer token" on webhook

- ✓ Verify callbackToken matches in OpenMRS subscription config

**Issue**: Notifications not sending

- ✓ Check Vault connection: `curl http://vault:8200/v1/sys/health`
- ✓ Check RabbitMQ queue: Access http://localhost:15672

**Issue**: Patient contact is NULL after 14 days

- ✓ Expected behavior! Data purge ran. Check audit_log for confirmation.

**Issue**: OpenMRS appointment update failed on cancellation

- ✓ Check OpenMRS FHIR API authentication
- ✓ Verify appointment still exists in OpenMRS

---

## 📚 API Reference

| Endpoint                                   | Method   | Purpose                                 |
| ------------------------------------------ | -------- | --------------------------------------- |
| `/api/organisations`                       | POST     | Register hospital                       |
| `/api/organisations/{id}`                  | GET      | Get hospital config                     |
| `/api/organisations/{id}`                  | PUT      | Update hospital config                  |
| `/api/organisations/{id}/provider`         | PUT      | Change communication provider           |
| `/api/organisations/{id}/logs`             | GET      | View message logs                       |
| `/api/organisations/{id}/statistics`       | GET      | Get delivery statistics                 |
| `/api/appointments/cancel/{orgId}/{token}` | GET/POST | Patient cancellation                    |
| `/api/compliance/retention-policy`         | GET      | Data retention details                  |
| `/api/compliance/security-policy`          | GET      | Security & encryption info              |
| `/fhir/webhook/{organisationId}`           | POST     | FHIR appointment webhook (from OpenMRS) |

---

## ✅ Compliance Summary

| Requirement                                | Status | Details                                                                  |
| ------------------------------------------ | ------ | ------------------------------------------------------------------------ |
| AES-256 encryption                         | ✅     | At rest + in transit (TLS 1.3)                                           |
| Patient cancellation link                  | ✅     | Included in every notification                                           |
| Auto-delete patient data                   | ✅     | 14-day purge job                                                         |
| Retain audit logs                          | ✅     | 1-year message log retention                                             |
| HL7 FHIR R4 compliance                     | ✅     | Full pipeline: receipt, validation, ACK, transformation, queueing, retry |
| No external dependencies for core function | ✅     | Works independently; providers are fallback                              |
| Multi-timezone support                     | ✅     | IANA timezone support                                                    |
| Multi-charset support                      | ✅     | UTF-8 + international characters                                         |
| Graceful provider fallback                 | ✅     | Exponential backoff + dead letter queue                                  |

---

**Version**: 2.0.0  
**Last Updated**: 2025-05-21  
**Maintainer**: Communication Module Team
