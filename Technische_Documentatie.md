# Technische documentatie voor beheerders

Deze documentatie beschrijft de stappen voor OpenMRS-beheerders om de koppeling met de Communicatie Module te realiseren. Hieronder worden de benodigde configuraties, Docker-opstartinstructies en een voorbeeldrequest toegelicht.

## 1. Configuratie van credentials

1. **Credentials instellen**
   - Open het bestand `communication-module/src/main/resources/application.yml`.
   - Vul de benodigde credentials in voor de koppeling, zoals gebruikersnaam, wachtwoord, en eventuele tokens.
   - Voorbeeld:
     ```yaml
     spring:
       rabbitmq:
         username: <gebruikersnaam>
         password: <wachtwoord>
         host: <rabbitmq-host>
         port: <rabbitmq-port>
     provider:
       url: <openmrs-url>
       username: <openmrs-gebruiker>
       password: <openmrs-wachtwoord>
     ```
   - Sla het bestand op.

2. **Secrets beheren**
   - Gebruik bij voorkeur omgevingsvariabelen of een secrets manager voor productieomgevingen.
   - Zie eventueel de `vault/` directory voor scripts om secrets te initialiseren.

## 2. Docker-opstartinstructie

1. **Docker Compose gebruiken**
   - Navigeer naar de root van het project.
   - Start de benodigde containers met:
     ```sh
     docker-compose up -d
     ```
   - Voor specifieke configuraties (zoals SSL of Grafana monitoring), gebruik de juiste compose-bestanden, bijvoorbeeld:
     ```sh
     docker-compose -f docker-compose.yml -f docker-compose.ssl.yml up -d
     ```

2. **Controleer de logs**
   - Bekijk de logs van de communicatie-module met:
     ```sh
     docker-compose logs -f communication-module
     ```

## 3. Voorbeeldrequest

Hieronder een voorbeeld van een POST-request naar de webhook endpoint van de communicatie-module:

```
POST /webhook/fhir HTTP/1.1
Host: <communicatie-module-host>
Content-Type: application/json
Authorization: Bearer <token>

{
  "resourceType": "Appointment",
  "id": "12345",
  "status": "booked",
  "participant": [
    {
      "actor": {
        "reference": "Patient/67890"
      },
      "status": "accepted"
    }
  ]
}
```

- **Endpoint**: `/webhook/fhir` (zie `FhirWebhookController.java`)
- **Authenticatie**: Bearer token of basis authenticatie, afhankelijk van de configuratie.
- **Body**: FHIR-resource, bijvoorbeeld een Appointment.

## 4. Overige tips

- Raadpleeg de README.md-bestanden in de submodules voor aanvullende informatie.
- Controleer of alle afhankelijkheden (RabbitMQ, OpenMRS, etc.) bereikbaar zijn.
- Voor monitoring en logging zijn extra configuraties beschikbaar in de `monitoring/` directory.
