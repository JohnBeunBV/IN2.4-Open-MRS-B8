# OpenMRS Communicatiemodule

SaaS notification module for OpenMRS — sends appointment reminders via SwiftSend, SecurePost, LegacyLink, and AsyncFlow.

## Quick Start (alles in één commando)

```bash
docker compose up --build
```

## Voorbeeld request om de module te testen

```bash
# 1. Registreer een organisatie
curl -u admin:admin -X POST http://localhost:8080/api/organisations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Ziekenhuis Utrecht",
    "openmrsBaseUrl": "http://openmrs:8080/openmrs",
    "providerType": "SWIFT_SEND",
    "callbackToken": "geheim-token-123",
    "timezone": "Europe/Amsterdam",
    "language": "nl"
  }'

# 2. Simuleer een FHIR Subscription webhook van OpenMRS
curl -X POST "http://localhost:8080/fhir/webhook/{organisatie-id-uit-stap-1}" \
  -H "Authorization: Bearer geheim-token-123" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Appointment",
    "id": "appt-12345",
    "status": "booked",
    "start": "2025-06-15T10:00:00+02:00",
    "comment": "Nuchter komen",
    "participant": [{
      "actor": {
        "reference": "Patient/p-001",
        "display": "Jan de Vries"
      },
      "status": "accepted"
    },{
      "actor": {
        "reference": "Location/loc-001",
        "display": "Polikliniek B, Kamer 12"
      },
      "status": "accepted"
    }],
    "extension": [{
      "url": "http://example.com/fhir/extension/patient-phone",
      "valueString": "+31612345678"
    }]
  }'
```

## Dashboards

| Service       | URL                          | Login        |
|---------------|------------------------------|--------------|
| App API       | http://localhost:8080        | admin/admin  |
| RabbitMQ UI   | http://localhost:15672       | guest/guest  |
| Grafana       | http://localhost:3000        | admin/admin  |
| Prometheus    | http://localhost:9090        | —            |
| Vault UI      | http://localhost:8200/ui     | token: root-token |
| Fake Providers| http://localhost:1337        | —            |
