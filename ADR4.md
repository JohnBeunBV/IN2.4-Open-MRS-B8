# ADR-004 — Asynchrone notificatieverwerking met RabbitMQ

| Veld   | Waarde     |
|--------|------------|
| Status | Accepted   |
| Sprint | 2          |

## Context

De communicatiemodule verwerkt notificaties voor afspraken — herinneringen 24 uur en 1 uur vóór een afspraak — en verstuurt deze via meerdere externe messaging providers. In een synchrone aanpak wordt de notificatie direct verstuurd vanuit de applicatieflow. Dit brengt een aantal problemen met zich mee:

- Trage externe providers veroorzaken trage responsetijden in de applicatie.
- Een tijdelijke provider-failure heeft direct impact op de verwerkingsflow.
- Geen robuuste retry-mechanismen.
- Berichten kunnen verloren gaan bij een tijdelijke storing.
- Moeilijk schaalbaar bij grote aantallen gelijktijdige notificaties.

## Overwogen opties

**Optie A — Synchrone verwerking**
Notificaties worden direct verstuurd vanuit de service die de trigger ontvangt.

- Voordeel: eenvoudig te implementeren.
- Nadeel: geen isolatie tussen de verwerkingsflow en externe providers. Geen retry-mechanisme. Niet schaalbaar. Berichten gaan verloren bij fouten.

**Optie B — RabbitMQ als message broker (gekozen)**
Een event-driven architectuur waarbij notificaties als berichten gepubliceerd worden op een queue en asynchroon verwerkt worden door een consumer.

- Voordeel: volledige ontkoppeling van producer en consumer. Ingebouwd retry-mechanisme via re-publish met verhoogde retry-counter. Dead-letter queue voor permanente failures. Berichten zijn persistent (durable queue). Schaalt horizontaal door meerdere consumers te draaien. Logging en auditing per verzendpoging.
- Nadeel: extra infrastructuurcomponent. Verhoogde complexiteit ten opzichte van een synchrone aanpak.

**Optie C — Apache Kafka**
Een gedistribueerd event-streaming platform.

- Nadeel: zwaarder in beheer en resourcegebruik dan RabbitMQ. Kafka is geoptimaliseerd voor event-sourcing op grote schaal, wat voor dit project overkill is. Complexere lokale ontwikkelomgeving.

## Beslissing

Wij kiezen voor **Optie B: RabbitMQ als message broker**. RabbitMQ biedt de benodigde betrouwbaarheid, retry-logica en ontkoppeling zonder de operationele overhead van Kafka. De architectuur bestaat uit:

- Een `NotificationProducer` die berichten publiceert op een `DirectExchange`.
- Een `NotificationConsumer` die berichten verwerkt via `@RabbitListener`.
- Een durable main queue met dead-letter configuratie en een message TTL van 1 uur.
- Een dead-letter exchange en dead-letter queue voor berichten die na maximaal 5 pogingen niet verwerkt konden worden.
- JSON-serialisatie via `Jackson2JsonMessageConverter`.
- Een `MessageLog` per verzendpoging voor auditing en debugging.

Elk bericht (`NotificationMessage`) bevat alle benodigde data — organisatie, provider, ontvanger, afspraakinformatie, retry-count — zodat de consumer volledig losgekoppeld is van andere services.

## Gevolgen

- De RabbitMQ-infrastructuur (exchange, queues, bindings) wordt geconfigureerd via `RabbitMQConfig` en opgestart via Docker Compose.
- Bij een mislukte verzendpoging wordt het bericht opnieuw gepubliceerd met een verhoogde `retryCount`. Na 5 pogingen wordt het bericht doorgestuurd naar de dead-letter queue en de notificatie gemarkeerd als `FAILED`.
- `setDefaultRequeueRejected(false)` voorkomt dat exceptions oneindig opnieuw gequeued worden.
- Elke verzendpoging wordt opgeslagen in `MessageLog` met provider-response, retry-count, foutmelding, status en timestamp.
- De keuze voor RabbitMQ is consistent met ADR-002 (Technology Stack).
