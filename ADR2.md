# ADR-002 — Technology Stack

| Veld   | Waarde     |
|--------|------------|
| Status | Accepted   |
| Sprint | 1          |

## Context

Nu vastgesteld is dat de communicatiemodule een zelfstandige SaaS-applicatie wordt (zie ADR-001), moeten we bepalen met welke technologieën de module gebouwd wordt. De keuzes moeten passen bij de niet-functionele eisen — HL7/FHIR R4, AES-256/TLS 1.3, multi-timezone, multi-charset, OpenTelemetry — én aansluiten bij de bestaande OpenMRS-stack en de capaciteiten van het team.

## Overwogen opties

| Onderdeel         | Gekozen              | Alternatieven                        |
|-------------------|----------------------|--------------------------------------|
| Backend           | Java (Spring Boot)   | Python/FastAPI, Node.js/NestJS, C#   |
| FHIR-verwerking   | HAPI FHIR            | Firely SDK (.NET), fhir.js           |
| Berichtenwachtrij | RabbitMQ             | Apache Kafka, ActiveMQ               |
| Database          | PostgreSQL           | MySQL, MongoDB                       |
| Secrets-beheer    | HashiCorp Vault      | AWS Secrets Manager, env-variables   |
| Monitoring        | OpenTelemetry + Grafana + Prometheus | Datadog, New Relic, ELK |
| Containerisatie   | Docker + Docker Compose | Kubernetes, Podman              |
| API-stijl         | REST + FHIR R4       | GraphQL, gRPC                        |

**Java / Spring Boot** sluit aan op de bestaande OpenMRS-stack (Java/Spring) en biedt de beste integratie met HAPI FHIR. Alternatieven zoals Python of Node.js missen volwaardige FHIR-bibliotheken en bedrijfsklare features.

**HAPI FHIR** is de de-facto standaard voor HL7 FHIR in Java, actief onderhouden en volledig compatibel met FHIR R4.

**RabbitMQ** is lichter dan Kafka, ondersteunt complexe routing via exchanges en queues, en is eenvoudig te draaien in Docker. Kafka is beter voor event-sourcing op grote schaal en is voor dit project overkill.

**PostgreSQL** biedt JSONB-ondersteuning (handig voor FHIR-resources), sterke transacties en goede Docker-ondersteuning.

**HashiCorp Vault** voldoet aan de eis dat credentials niet in code of configuratiebestanden staan. Het ondersteunt AES-256-encryptie en draait zelfstandig in Docker.

**OpenTelemetry + Grafana + Prometheus** zijn vendor-neutraal en open-source. OpenTelemetry is expliciet vereist door de opdracht.

**Docker Compose** volstaat voor de projectscope en maakt lokale ontwikkeling eenvoudig. Kubernetes is voor een project van deze omvang overkill.

## Beslissing

De module wordt gebouwd met **Java/Spring Boot**, **HAPI FHIR**, **RabbitMQ**, **PostgreSQL**, **HashiCorp Vault** en **OpenTelemetry + Grafana + Prometheus**, allemaal gedraaid via **Docker Compose**.

## Gevolgen

- De gehele codebase wordt in Java geschreven. Teamleden die primair Python of JavaScript kennen worden ingewerkt op Spring Boot.
- HAPI FHIR vereist kennis van de FHIR R4-specificatie; dit is een leerdoel voor het team.
- Een Docker Compose-configuratie wordt opgeleverd als onderdeel van de codebase.
- HashiCorp Vault moet geconfigureerd zijn voordat de eerste integratiepunten opgezet kunnen worden.
