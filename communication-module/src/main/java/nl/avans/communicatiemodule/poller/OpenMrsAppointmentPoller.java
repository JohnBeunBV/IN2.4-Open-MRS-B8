package nl.avans.communicatiemodule.poller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Polls every active OpenMRS organisatie via de REST v1 Appointment API.
 *
 * Achtergrond: de FHIR2-module in de standaard OpenMRS O3 referentie-
 * applicatie ondersteunt geen Appointment Subscriptions. Als fallback
 * (of primaire integratie) pollen we daarom elke paar minuten de
 * REST v1 endpoint: POST /ws/rest/v1/appointment/search
 *
 * Schakel in via: app.poller.enabled=true (standaard: true)
 * Schakel uit via: app.poller.enabled=false (als FHIR webhooks wél werken)
 *
 * Veerkracht:
 *  - Circuit breaker per organisatie: na 5 opeenvolgende fouten, 2 min. pauze
 *  - Duplicate guard: al bekende appointments met ongewijzigde status worden overgeslagen
 *  - Gecancelde appointments worden direct als CANCELLED gemarkeerd
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OpenMrsAppointmentPoller {

    /** Hoeveel uur vooruit zoeken naar afspraken (30 dagen). */
    private static final int POLL_WINDOW_HOURS = 720;

    /** Circuit breaker: na hoeveel opeenvolgende fouten de kring open gaat. */
    private static final int CIRCUIT_OPEN_THRESHOLD = 5;

    /** Hoe lang de circuit breaker open blijft (milliseconden). */
    private static final long CIRCUIT_OPEN_WAIT_MS = 120_000;

    private final OrganisationConfigRepository organisationRepository;
    private final AppointmentNotificationRepository notificationRepository;
    private final RestTemplate restTemplate;

    // Circuit breaker state per organisatie-UUID (in-memory)
    private final Map<UUID, Integer>  consecutiveFailures = new HashMap<>();
    private final Map<UUID, Long>     circuitOpenedAt     = new HashMap<>();

    // Bijhouden welke appointment-UUID's we al gezien hebben en met welke status
    // Key: "orgId::appointmentUuid", Value: laatste bekende status
    private final Map<String, String> seenAppointments = new HashMap<>();

    public OpenMrsAppointmentPoller(
            OrganisationConfigRepository organisationRepository,
            AppointmentNotificationRepository notificationRepository,
            RestTemplateBuilder restTemplateBuilder,
            @Value("${app.poller.connect-timeout-seconds:5}") int connectTimeout,
            @Value("${app.poller.read-timeout-seconds:10}")   int readTimeout) {

        this.organisationRepository  = organisationRepository;
        this.notificationRepository  = notificationRepository;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(connectTimeout))
                .setReadTimeout(Duration.ofSeconds(readTimeout))
                .build();
    }

    /**
     * Hoofd polling loop – draait elke 2 minuten (instelbaar).
     */
    @Scheduled(
        fixedDelayString  = "${app.poller.interval-ms:120000}",
        initialDelayString = "${app.poller.initial-delay-ms:30000}"
    )
    public void pollAllOrganisations() {
        List<OrganisationConfig> activeOrgs = organisationRepository.findByActiveTrue();
        if (activeOrgs.isEmpty()) {
            log.debug("Poller: geen actieve organisaties gevonden, sla over");
            return;
        }
        log.debug("Poller: {} actieve organisatie(s) controleren", activeOrgs.size());
        activeOrgs.forEach(this::pollOrganisation);
    }

    // ── Per-organisatie poll ──────────────────────────────────────────────────

    private void pollOrganisation(OrganisationConfig org) {
        UUID orgId = org.getId();

        if (isCircuitOpen(orgId)) {
            log.warn("Poller [org={}]: circuit OPEN – overgeslagen", org.getName());
            return;
        }

        Instant now       = Instant.now();
        Instant windowEnd = now.plus(POLL_WINDOW_HOURS, ChronoUnit.HOURS);

        List<RestAppointment> appointments;
        try {
            appointments = fetchAppointments(org, now, windowEnd);
            resetCircuit(orgId);
        } catch (Exception ex) {
            recordFailure(orgId, org.getName(), ex);
            return;
        }

        if (appointments.isEmpty()) {
            log.debug("Poller [org={}]: geen afspraken in komende {}u", org.getName(), POLL_WINDOW_HOURS);
            return;
        }

        log.info("Poller [org={}]: {} afspraken gevonden", org.getName(), appointments.size());
        int saved = 0;
        for (RestAppointment apt : appointments) {
            try {
                if (processAppointment(org, apt)) saved++;
            } catch (Exception ex) {
                log.error("Poller [org={}]: fout bij verwerken uuid={}: {}",
                        org.getName(), apt.getUuid(), ex.getMessage());
            }
        }
        log.info("Poller [org={}]: {}/{} afspraken verwerkt", org.getName(), saved, appointments.size());
    }

    // ── Afspraak verwerken ───────────────────────────────────────────────────

    @Transactional
    protected boolean processAppointment(OrganisationConfig org, RestAppointment apt) {
        if (apt.getUuid() == null || apt.getStartDateTime() == null) return false;

        String seenKey     = org.getId() + "::" + apt.getUuid();
        String currentStatus = apt.getStatus() != null ? apt.getStatus() : "Scheduled";
        String previousStatus = seenAppointments.get(seenKey);

        // Overslaan als status niet gewijzigd is
        if (previousStatus != null && previousStatus.equalsIgnoreCase(currentStatus)) {
            log.debug("Poller: geen wijziging uuid={}", apt.getUuid());
            return false;
        }

        seenAppointments.put(seenKey, currentStatus);

        // Gecanceld of gemist → markeer bestaande notificatie als geannuleerd
        if (isCancelled(currentStatus)) {
            notificationRepository.findByFhirAppointmentId(apt.getUuid()).ifPresent(n -> {
                n.setStatus(NotificationStatus.CANCELLED);
                notificationRepository.save(n);
                log.info("Poller: afspraak {} gecanceld, notificaties geblokkeerd", apt.getUuid());
            });
            return true;
        }

        // Verleden afspraken overslaan
        if (apt.getStartDateTime().isBefore(Instant.now())) {
            log.debug("Poller: afspraak {} al gestart/verleden, overgeslagen", apt.getUuid());
            return false;
        }

        // Contactgegevens ophalen
        String phone = fetchPatientPhone(org, apt.getPatientUuid());

        // Upsert
        AppointmentNotification notification = notificationRepository
                .findByFhirAppointmentId(apt.getUuid())
                .orElseGet(AppointmentNotification::new);

        notification.setFhirAppointmentId(apt.getUuid());
        notification.setOrganisationId(org.getId());
        notification.setPatientPhone(phone != null ? phone : "+00000000000");
        notification.setPatientName(apt.getPatientName());
        notification.setAppointmentStart(apt.getStartDateTime());
        notification.setAppointmentLocation(apt.getLocationName());
        notification.setAppointmentInstructions(apt.getComments());

        Instant start = apt.getStartDateTime();
        notification.setNotifyAt24h(start.minus(24, ChronoUnit.HOURS));
        notification.setNotifyAt1h(start.minus(1, ChronoUnit.HOURS));

        // Al voorbij het 24u-moment? Markeer als verstuurd
        if (notification.getNotifyAt24h().isBefore(Instant.now())) {
            notification.setSent24h(true);
        }

        notification.setExpiresAt(start.plus(14, ChronoUnit.DAYS));

        if (notification.getStatus() == null || notification.getStatus() == NotificationStatus.FAILED) {
            notification.setStatus(NotificationStatus.PENDING);
        }

        notificationRepository.save(notification);
        log.info("Poller: notificatie opgeslagen uuid={} start={}", apt.getUuid(), start);
        return true;
    }

    // ── OpenMRS REST v1 ──────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<RestAppointment> fetchAppointments(OrganisationConfig org,
                                                    Instant from, Instant to) {
        String url = org.getOpenmrsBaseUrl() + "/ws/rest/v1/appointment/search";

        DateTimeFormatter fmt = DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC);

        Map<String, String> body = Map.of(
                "startDate", fmt.format(from),
                "endDate",   fmt.format(to));

        HttpHeaders headers = buildHeaders(org);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<List> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    List.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("OpenMRS gaf HTTP " + response.getStatusCode());
            }

            return ((List<Map<String, Object>>) response.getBody())
                    .stream()
                    .map(this::mapToRestAppointment)
                    .toList();

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new RuntimeException("OpenMRS authenticatie mislukt – controleer username/password", e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException("OpenMRS niet bereikbaar: " + e.getMessage(), e);
        }
    }

    /**
     * Haal het telefoonnummer op via GET /ws/rest/v1/person/{uuid}?v=full.
     * Geeft null terug als het ophalen mislukt of geen nummer beschikbaar is.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String fetchPatientPhone(OrganisationConfig org, String patientUuid) {
        if (patientUuid == null) return null;
        String url = org.getOpenmrsBaseUrl() + "/ws/rest/v1/person/" + patientUuid + "?v=full";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders(org)),
                    Map.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;

            List<Map<String, Object>> attrs =
                    (List<Map<String, Object>>) resp.getBody().getOrDefault("attributes", List.of());

            for (Map<String, Object> attr : attrs) {
                Map<String, Object> typeMap = (Map<String, Object>) attr.get("attributeType");
                if (typeMap == null) continue;
                String display = (String) typeMap.get("display");
                Object value   = attr.get("value");
                if ("Telephone Number".equals(display) && value != null) {
                    return value.toString();
                }
            }
        } catch (Exception ex) {
            log.warn("Poller: kon telefoonnummer niet ophalen voor uuid={}: {}", patientUuid, ex.getMessage());
        }
        return null;
    }

    // ── Hulpmethoden ─────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(OrganisationConfig org) {
        HttpHeaders headers = new HttpHeaders();
        // Basic Auth met de credentials van de organisatie
        String creds = org.getOpenmrsUsername() + ":" + org.getOpenmrsPassword();
        String encoded = Base64.getEncoder().encodeToString(creds.getBytes());
        headers.set("Authorization", "Basic " + encoded);
        headers.set("Accept", "application/json");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private RestAppointment mapToRestAppointment(Map<String, Object> raw) {
        RestAppointment apt = new RestAppointment();
        apt.setUuid((String) raw.get("uuid"));
        apt.setStatus((String) raw.get("status"));
        apt.setComments((String) raw.get("comments"));

        Object start = raw.get("startDateTime");
        if (start instanceof Number n) {
            apt.setStartDateTime(Instant.ofEpochMilli(n.longValue()));
        }

        Map<String, Object> patient = (Map<String, Object>) raw.get("patient");
        if (patient != null) {
            apt.setPatientUuid((String) patient.get("uuid"));
            apt.setPatientName((String) patient.get("name"));
        }

        Map<String, Object> location = (Map<String, Object>) raw.get("location");
        if (location != null) {
            apt.setLocationName((String) location.get("name"));
        }

        return apt;
    }

    private boolean isCancelled(String status) {
        if (status == null) return false;
        return status.equalsIgnoreCase("Cancelled") || status.equalsIgnoreCase("Missed");
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────

    private boolean isCircuitOpen(UUID orgId) {
        int failures = consecutiveFailures.getOrDefault(orgId, 0);
        if (failures < CIRCUIT_OPEN_THRESHOLD) return false;
        long elapsed = System.currentTimeMillis() - circuitOpenedAt.getOrDefault(orgId, 0L);
        if (elapsed >= CIRCUIT_OPEN_WAIT_MS) {
            log.info("Poller: circuit half-open voor org={}, herstelpoging", orgId);
            consecutiveFailures.put(orgId, CIRCUIT_OPEN_THRESHOLD - 1);
            return false;
        }
        return true;
    }

    private void resetCircuit(UUID orgId) {
        consecutiveFailures.put(orgId, 0);
    }

    private void recordFailure(UUID orgId, String orgName, Exception ex) {
        int failures = consecutiveFailures.merge(orgId, 1, Integer::sum);
        log.error("Poller [org={}]: poll mislukt (poging #{}): {}", orgName, failures, ex.getMessage());
        if (failures >= CIRCUIT_OPEN_THRESHOLD) {
            circuitOpenedAt.put(orgId, System.currentTimeMillis());
            log.error("Poller [org={}]: circuit OPEN na {} mislukkingen. Pauze {}s.",
                    orgName, failures, CIRCUIT_OPEN_WAIT_MS / 1000);
        }
    }

    // ── POJO voor REST v1 response ───────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RestAppointment {
        private String  uuid;
        private String  status;
        private Instant startDateTime;
        private String  patientUuid;
        private String  patientName;
        private String  locationName;
        private String  comments;

        public String  getUuid()          { return uuid; }
        public void    setUuid(String v)  { this.uuid = v; }
        public String  getStatus()          { return status; }
        public void    setStatus(String v)  { this.status = v; }
        public Instant getStartDateTime()          { return startDateTime; }
        public void    setStartDateTime(Instant v) { this.startDateTime = v; }
        public String  getPatientUuid()          { return patientUuid; }
        public void    setPatientUuid(String v)  { this.patientUuid = v; }
        public String  getPatientName()          { return patientName; }
        public void    setPatientName(String v)  { this.patientName = v; }
        public String  getLocationName()          { return locationName; }
        public void    setLocationName(String v)  { this.locationName = v; }
        public String  getComments()          { return comments; }
        public void    setComments(String v)  { this.comments = v; }
    }
}
