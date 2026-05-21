# ADR-003 — Uitwisseling van afspraakdata tussen OpenMRS en de communicatiemodule

| Veld   | Waarde     |
|--------|------------|
| Status | Accepted   |
| Sprint | 1          |

## Context

De communicatiemodule heeft afspraakdata nodig van OpenMRS-instanties om notificaties te kunnen versturen. De vraag is via welk mechanisme deze data binnenkomt. De oplossing moet aansluiten op de HL7/FHIR-eis, near-realtime events ondersteunen en schaalbaar zijn naar meerdere organisaties.

## Overwogen opties

**Optie A — Polling via de OpenMRS REST API**
De module bevraagt periodiek de REST API van OpenMRS voor nieuwe of gewijzigde afspraken.

- Voordeel: eenvoudig te implementeren, geen wijzigingen aan OpenMRS nodig.
- Nadeel: hoge belasting op de OpenMRS-instantie. Maximale vertraging gelijk aan het pollinginterval. Schaalt slecht naar meerdere instanties.

**Optie B — Webhook (push) vanuit OpenMRS**
OpenMRS stuurt een HTTP-callback naar de communicatiemodule bij elke wijziging in een afspraak.

- Voordeel: near-realtime, lage belasting op OpenMRS.
- Nadeel: vereist configuratie van de OpenMRS Event Module of een eigen module per instantie, zonder aansluiting op een standaard. Events gaan verloren bij downtime van de communicatiemodule als OpenMRS geen retry-logica heeft.

**Optie C — FHIR Subscription (gekozen)**
De communicatiemodule registreert een FHIR Subscription-resource bij de OpenMRS FHIR2-module. OpenMRS stuurt automatisch notificaties bij wijzigingen in `Appointment`-resources.

- Voordeel: native aansluiting op de HL7/FHIR-standaard zoals vereist. Near-realtime. Elke OpenMRS-instantie heeft een eigen subscription, waardoor het goed schaalt. Compatibel met OpenMRS 2.7.x via de FHIR2-module.
- Nadeel: de FHIR2-module moet actief zijn bij elke OpenMRS-instantie. De callback-URL vereist TLS.

**Optie D — Directe databasetoegang**
De communicatiemodule leest rechtstreeks uit de OpenMRS-database.

- Nadeel: harde koppeling aan het interne datamodel van OpenMRS. Niet ondersteund, niet gedocumenteerd en potentieel schadelijk voor data-integriteit.

## Beslissing

Wij kiezen voor **Optie C: FHIR Subscription**. Deze aanpak sluit als enige direct aan op de HL7/FHIR-eis, levert near-realtime events en schaalt naar meerdere OpenMRS-instanties zonder aanpassingen aan de standaard. Polling (A) schaalt niet en belast OpenMRS onnodig. Webhooks (B) vereisen custom configuratie buiten de FHIR-standaard. Directe databasetoegang (D) is architectureel onjuist.

## Gevolgen

- De communicatiemodule exposed een FHIR-compatibel webhook-eindpunt (HTTPS + TLS 1.3).
- Bij opstarten registreert de module automatisch een `Subscription`-resource bij elke geconfigureerde OpenMRS-instantie.
- Inkomende events worden direct gepersisteerd in de database en via RabbitMQ verwerkt (zie ADR-004), zodat downtime van de communicatiemodule geen events verloren laat gaan.
- Bij downtime van OpenMRS worden geen nieuwe events ontvangen; de scheduler verwerkt reeds geplande berichten en de subscription hervat automatisch na herstel.
- Authenticatie van het webhook-eindpunt volgt de FHIR Subscription-standaard (Bearer token, opgeslagen in HashiCorp Vault).
- Documentatie voor beheerders beschrijft hoe de FHIR2-module geactiveerd en geconfigureerd moet worden.
