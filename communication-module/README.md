# 🏥 OpenMRS Communication Module v2.0

**Enterprise-grade SaaS healthcare notification service** for OpenMRS appointment reminders with full encryption, GDPR compliance, and multi-hospital support.

## ✨ What's New in v2.0

### 🔐 Complete Security Overhaul

- **AES-256-GCM encryption** for all patient data at rest
- **TLS 1.3** enforced for all data in transit
- **Vault-managed encryption keys** with automatic rotation
- Field-level encryption for maximum privacy

### 🏥 Hospital Provider Selection

- Each hospital chooses their preferred communication provider
- **4 providers available**: SwiftSend (SMS/WhatsApp), SecurePost (encrypted mail), LegacyLink (fax/legacy), AsyncFlow (flexible)
- Dynamic provider switching without losing messages
- Provider statistics and health monitoring

### 📋 GDPR & Compliance

- ✅ **14-day automatic purging** of patient PII (names, contacts, appointment details)
- ✅ **1-year audit retention** for message logs (no PII, for compliance verification)
- ✅ **FHIR R4 HL7 compliance** with full message pipeline
- ✅ **Multi-timezone support** for global hospitals
- ✅ **UTF-8 & international charset support**

### 🔗 Patient Appointment Cancellation

- Patients receive secure cancellation links in notifications
- One-click appointment cancellation that updates OpenMRS
- Automatic synchronisation with patient records

### 🛡️ Resilience & Failover

- Exponential backoff retry with jitter
- Dead letter queue for failed messages
- Provider failover support
- Automatic message queue purging after 14 days

## 🚀 Quick Start

### Prerequisites

```bash
# Ensure these services are running
- OpenMRS with FHIR enabled
- PostgreSQL 13+
- RabbitMQ 3.8+
- HashiCorp Vault (or compatible)
- Docker & Docker Compose
```

### One-Command Startup

```bash
docker compose up --build
```

This starts:

- Communication Module (port 8080)
- PostgreSQL (port 5432)
- RabbitMQ (port 5672)
- Vault (port 8200)
- Prometheus (port 9090)
- Grafana (port 3000)
- Provider simulators (port 1337)

## 📊 Default Credentials

| Service            | URL                      | Username | Password          |
| ------------------ | ------------------------ | -------- | ----------------- |
| **API**            | http://localhost:8080    | admin    | admin             |
| **RabbitMQ**       | http://localhost:15672   | guest    | guest             |
| **Grafana**        | http://localhost:3000    | admin    | admin             |
| **Vault**          | http://localhost:8200/ui | —        | token: root-token |
| **Fake Providers** | http://localhost:1337    | —        | —                 |

## 🏥 Hospital Registration (5 minutes)

### Step 1: Register Hospital

```bash
# Get your organisation UUID first
ORG_ID=$(curl -s -u admin:admin -X POST http://localhost:8080/api/organisations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Ziekenhuis Utrecht",
    "openmrsBaseUrl": "http://openmrs:8080/openmrs",
    "providerType": "SWIFT_SEND",
    "callbackToken": "ziekenhuis-secret-token-2025",
    "timezone": "Europe/Amsterdam",
    "language": "nl"
  }' | jq -r '.id')

echo "Hospital registered: $ORG_ID"
```

### Step 2: Configure FHIR Subscription in OpenMRS

In your OpenMRS FHIR module configuration:

```json
{
  "resourceType": "Subscription",
  "status": "active",
  "criteria": "Appointment?status=booked",
  "channel": {
    "type": "rest-hook",
    "endpoint": "http://communicatiemodule:8080/fhir/webhook/{organisationId}",
    "header": ["Authorization: Bearer ziekenhuis-secret-token-2025"]
  }
}
```

**Replace `{organisationId}` with your hospital UUID**

### Step 3: Test with Sample Appointment

```bash
curl -X POST "http://localhost:8080/fhir/webhook/$ORG_ID" \
  -H "Authorization: Bearer ziekenhuis-secret-token-2025" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Appointment",
    "id": "appt-001",
    "status": "booked",
    "start": "2025-06-15T10:00:00+02:00",
    "end": "2025-06-15T10:30:00+02:00",
    "comment": "Nuchter komen. Voorbereiding nodig.",
    "participant": [
      {
        "actor": {
          "reference": "Patient/pat-001",
          "display": "Jan de Vries"
        },
        "status": "accepted"
      },
      {
        "actor": {
          "reference": "Location/loc-001",
          "display": "Polikliniek B, Kamer 12"
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

✅ Response: `ACK` (HTTP 200)

### Step 4: Monitor Notifications

**Check pending notifications:**

```bash
curl -s http://localhost:8080/api/organisations/$ORG_ID/logs | jq '.'
```

**View Grafana dashboards:**

- http://localhost:3000 (admin/admin)
- Default dashboards for notification metrics

**RabbitMQ queue depth:**

- http://localhost:15672 → Queues → notification.queue

## 🔐 Encryption Details

### Patient Data Encrypted

```
✓ Patient name:         "Jan de Vries"
✓ Patient contact:      "+31612345678" or "jan@example.com"
✓ Appointment details:  "15 juni 2025, 10:00-10:30, Polikliniek B Kamer 12, Nuchter komen"
✗ Message logs:          NO PII stored (only delivery status)
✗ Cancellation token:    Opaque UUID (not tied to patient identity)
```

### Encryption Algorithm

```
Algorithm:  AES-256-GCM (Authenticated Encryption)
Key Size:   256 bits
IV Length:  96 bits (unique per encryption)
Tag Length: 128 bits (authentication tag)
Format:     BASE64(IV + CIPHERTEXT + TAG)

Each field encrypted independently → prevents pattern matching
```

### Key Management

```
Master key location:  Vault → secret/communicatiemodule/organisations/{orgId}/master-key
Key rotation:         Every 90 days (automatic)
Backup:               Encrypted backups stored in Vault
Recovery:             Use Vault unseal key
```

## 📋 FHIR HL7 Compliance Pipeline

```
1. RECEIPT (HTTP POST)
   └─ Validate Bearer token
   └─ Parse FHIR resource
   └─ Return HTTP 200 ACK

2. VALIDATION
   └─ Extract: start time, patient, location, instructions
   └─ Check: future appointment, status=booked, patient contact exists

3. ENCRYPTION & STORAGE
   └─ AES-256 encrypt patient data
   └─ Generate unique cancellation token
   └─ Store in PostgreSQL

4. SCHEDULING (every 30 seconds)
   └─ Poll for 24h-before appointments
   └─ Poll for 1h-before appointments
   └─ Queue notifications to RabbitMQ

5. TRANSFORMATION
   └─ Decrypt patient data
   └─ Format message (patient timezone, language)
   └─ Add cancellation link
   └─ Create provider-specific payload

6. QUEUEING
   └─ RabbitMQ: notification.queue
   └─ TTL: 14 days (automatic expiry)

7. RETRY
   └─ Attempt 1: 2 seconds
   └─ Attempt 2: 4 seconds
   └─ Attempt 3: 8 seconds
   └─ Attempt 4: 16 seconds
   └─ Attempt 5: 32 seconds
   └─ After 5 failures → Dead Letter Queue
```

## 🔗 Patient Cancellation

Patients receive notification:

```
📧 Subject: Afspraak herinnering – Ziekenhuis Utrecht

Uw afspraak:
📅 15 juni 2025, 10:00-10:30 uur
📍 Polikliniek B, Kamer 12
📋 Nuchter komen

👉 Annuleren: https://communicatiemodule/api/appointments/cancel/{orgId}/{token}
```

**Patient clicks link:**

```
1. Validates cancellation token (unique, opaque UUID)
2. Updates OpenMRS appointment status to CANCELLED
3. Confirms: "✓ Afspraak geannuleerd"
4. Notifies clinician via OpenMRS workflow
```

## 🛡️ GDPR Compliance

### Automatic Data Purging

**Patient Data (14 days)**

```bash
# Scheduled: Daily at 2 AM
# Action: Nullify encrypted fields
UPDATE appointment_notification
SET patient_contact_encrypted = NULL,
    patient_name_encrypted = NULL,
    appointment_details_encrypted = NULL
WHERE expires_at <= NOW()
```

**Message Logs (1 year)**

```bash
# Scheduled: Daily at 3 AM
# Action: Delete audit trail
DELETE FROM message_log WHERE expires_at <= NOW()
```

### Compliance Endpoints

```bash
# Retention policy
curl http://localhost:8080/api/compliance/retention-policy

# Security details
curl http://localhost:8080/api/compliance/security-policy

# FHIR HL7 compliance
curl http://localhost:8080/api/compliance/hl7-compliance

# Encryption details
curl http://localhost:8080/api/compliance/encryption-details
```

## 🔄 Hospital Provider Management

### View Available Providers

```bash
curl http://localhost:8080/api/organisations/providers/available
```

Response:

```json
[
  {
    "name": "SWIFT_SEND",
    "description": "SwiftSend - High-speed SMS delivery",
    "capabilities": ["SMS", "MMS", "WhatsApp", "24/7 delivery"]
  },
  {
    "name": "SECURE_POST",
    "description": "SecurePost - Secure postal notifications",
    "capabilities": ["Encrypted mail", "Registered delivery", "GDPR compliant"]
  },
  {
    "name": "LEGACY_LINK",
    "description": "LegacyLink - Integration with legacy systems",
    "capabilities": ["Fax", "Phone", "Email", "Legacy system support"]
  },
  {
    "name": "ASYNC_FLOW",
    "description": "AsyncFlow - Asynchronous message processing",
    "capabilities": [
      "Queued processing",
      "Retry logic",
      "Multi-channel support"
    ]
  }
]
```

### Change Provider

```bash
# Switch to SecurePost
curl -u admin:admin -X PUT http://localhost:8080/api/organisations/$ORG_ID/provider \
  -H "Content-Type: application/json" \
  -d '{"providerType": "SECURE_POST"}'
```

### View Statistics

```bash
curl http://localhost:8080/api/organisations/$ORG_ID/statistics
```

Response:

```json
{
  "organisationId": "...",
  "totalNotifications": 1245,
  "successfulNotifications": 1223,
  "failedNotifications": 22,
  "successRate": 98.23
}
```

## 📊 Monitoring & Debugging

### Check Notification Status

```bash
# List all notifications for an organisation
curl http://localhost:8080/api/organisations/$ORG_ID/logs | jq '.'

# Filter by status
curl "http://localhost:8080/api/organisations/$ORG_ID/logs?status=PENDING" | jq '.'

# Check specific notification
curl http://localhost:8080/api/organisations/$ORG_ID/logs?appointmentId=appt-001
```

### Database Queries

```sql
-- Pending notifications
SELECT id, fhir_appointment_id, status, notify_at_24h, notify_at_1h
FROM appointment_notification
WHERE organisation_id = '{ORG_ID}' AND status = 'PENDING';

-- Recent message logs
SELECT id, provider_type, status, sent_at, retry_count
FROM message_log
WHERE organisation_id = '{ORG_ID}'
ORDER BY created_at DESC LIMIT 20;

-- Encryption check
SELECT COUNT(*) as encrypted_records
FROM appointment_notification
WHERE patient_contact_encrypted IS NOT NULL;
```

### Logs

```bash
# Full logs
docker logs communicatiemodule

# Follow logs
docker logs -f communicatiemodule

# Specific organisation
docker logs communicatiemodule | grep "{ORG_ID}"

# Encryption operations
docker logs communicatiemodule | grep -i "encrypt"
```

## 🧪 Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify -P integration
```

### Load Testing (100 concurrent notifications)

```bash
# Generate 100 test appointments
for i in {1..100}; do
  curl -X POST "http://localhost:8080/fhir/webhook/$ORG_ID" \
    -H "Authorization: Bearer ziekenhuis-secret-token-2025" \
    -H "Content-Type: application/fhir+json" \
    -d "{ ... }"  # See example above
done

# Monitor queue
watch -n 1 'curl -s http://localhost:15672/api/queues/%2f/notification.queue -u guest:guest | jq .messages_ready'
```

## 🚀 Production Deployment

### Docker Image Build

```bash
docker build -t communicatiemodule:2.0 .
docker tag communicatiemodule:2.0 your-registry/communicatiemodule:latest
docker push your-registry/communicatiemodule:latest
```

### Kubernetes Deployment

```bash
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/configmap.yml
kubectl apply -f k8s/secret.yml
kubectl apply -f k8s/deployment.yml
kubectl apply -f k8s/service.yml
```

### SSL/TLS Configuration

```nginx
# Reverse proxy enforces TLS 1.3
server {
    listen 443 ssl http2;
    ssl_protocols TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location /api {
        proxy_pass http://communicatiemodule:8080;
    }
}
```

### Environment Variables (Production)

```bash
# Database
DATABASE_URL=postgresql://prod-db:5432/comm_prod
DATABASE_USER=commapp
DATABASE_PASSWORD=<secure-password>

# Vault
VAULT_ADDR=https://vault.example.com
VAULT_TOKEN=<production-token>

# RabbitMQ
RABBITMQ_HOST=rabbitmq.prod.local
RABBITMQ_USER=commapp
RABBITMQ_PASSWORD=<secure-password>

# Provider APIs
SWIFTSEND_API_KEY=<key>
SECUREPOST_USERNAME=<user>
SECUREPOST_PASSWORD=<pass>

# Security
WEBHOOK_TOKEN=<generated-secure-token>
FHIR_CALLBACK_BASE_URL=https://notifications.example.com
```

## 📚 API Reference

See [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) for comprehensive API documentation.

## 🔧 Configuration

See [application.yml](src/main/resources/application.yml) for all configuration options.

## 📄 License

Licensed under the Mozilla Public License 2.0 (MPL-2.0)

## 🤝 Support

For issues, questions, or contributions, please contact:

- **Email**: support@communicatiemodule.example.com
- **Slack**: #openmrs-notifications
- **Jira**: https://jira.example.com/browse/COMMMOD

---

**Version**: 2.0.0  
**Release Date**: 2025-05-21  
**Status**: ✅ Production Ready
