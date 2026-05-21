# ADR-001 — Architectuurkeuze: Zelfstandige module vs. ingebouwde module

| Veld   | Waarde     |
|--------|------------|
| Status | Accepted   |
| Sprint | 1          |

## Context

De communicatiemodule moet notificaties kunnen versturen voor meerdere OpenMRS-organisaties wereldwijd. De opdrachtgever wil de module als SaaS-product aanbieden. De kernvraag is of de module gebouwd wordt als een ingebouwde OpenMRS-module (geïnstalleerd binnen de OpenMRS-instantie zelf) of als een volledig zelfstandige applicatie die losstaat van OpenMRS.

## Overwogen opties

**Optie A — Ingebouwde OpenMRS-module**
De module wordt geïnstalleerd als een .omod-pakket binnen elke afzonderlijke OpenMRS-instantie.

- Voordeel: geen externe koppeling nodig; directe toegang tot OpenMRS-data.
- Nadeel: elke organisatie beheert een eigen instantie van de module. Geen centrale monitoring, geen gedeelde abonnementenlogica, en updates moeten per instantie uitgerold worden. Niet geschikt voor een SaaS-model.

**Optie B — Zelfstandige SaaS-applicatie (gekozen)**
De module draait als een onafhankelijke applicatie (Docker/Kubernetes) en koppelt via een API aan meerdere OpenMRS-instanties.

- Voordeel: één centrale deploymentomgeving voor alle organisaties. Vrije technologiekeuze, niet gebonden aan de OpenMRS-runtime. Centrale monitoring, logging en abonnementsbeheer. Onafhankelijk schaalbaar. Eenvoudiger uitbreidbaar met nieuwe notificatieproviders.
- Nadeel: vereist een beveiligde integratie-API tussen OpenMRS en de module. Extra afhankelijkheid van externe netwerkconnectiviteit.

## Beslissing

Wij kiezen voor **Optie B: een zelfstandige SaaS-applicatie**. Alleen deze aanpak maakt het mogelijk om meerdere OpenMRS-organisaties vanuit één centrale omgeving te bedienen, wat de kern is van het SaaS-aanbod. Een ingebouwde module maakt centraal beheer en gedeelde infrastructuur structureel onmogelijk.

## Gevolgen

- Er moet een beveiligde API-koppeling ontworpen worden tussen OpenMRS-instanties en de module (zie ADR-003).
- De module heeft een eigen database, berichtenwachtrij en monitoringstack nodig (zie ADR-002 en ADR-004).
- Technisch beheerders van OpenMRS-instanties hebben documentatie nodig om de koppeling te configureren.
