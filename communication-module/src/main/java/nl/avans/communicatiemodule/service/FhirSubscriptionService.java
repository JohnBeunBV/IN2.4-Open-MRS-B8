package nl.avans.communicatiemodule.service;

import ca.uhn.fhir.context.FhirContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FhirSubscriptionService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 5_000L;

    private final FhirContext fhirContext;
    private final WebClient webClient;
    private final OrganisationConfigRepository organisationRepository;

    @Value("${app.fhir.callback-base-url}")
    private String callbackBaseUrl;

    @Value("${app.fhir.callback-path}")
    private String callbackPath;

    @EventListener(ApplicationReadyEvent.class)
    public void registerAllSubscriptions() {
        List<OrganisationConfig> activeOrgs = organisationRepository.findByActiveTrue();
        log.info("Registering FHIR Subscriptions for {} active organisations", activeOrgs.size());
        activeOrgs.forEach(this::registerWithRetry);
    }

    public void registerWithRetry(OrganisationConfig org) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                registerSubscription(org);
                return;
            } catch (Exception ex) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("FHIR Subscription registration failed after {} attempts for org={}: {}",
                              MAX_ATTEMPTS, org.getName(), ex.getMessage());
                } else {
                    log.warn("Subscription registration attempt {}/{} failed for org={}, retrying in {}ms: {}",
                             attempt, MAX_ATTEMPTS, org.getName(), backoff, ex.getMessage());
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    backoff = Math.min(backoff * 2, 60_000L);
                }
            }
        }
    }

    public void registerSubscription(OrganisationConfig org) {
        String callbackUrl = callbackBaseUrl + callbackPath + "/" + org.getId();
        Subscription subscription = buildSubscription(org, callbackUrl);
        String payload = fhirContext.newJsonParser().encodeResourceToString(subscription);

        log.info("Registering FHIR Subscription for org={} at {}", org.getName(), org.getOpenmrsBaseUrl());

        webClient.put()
                .uri(org.getOpenmrsBaseUrl() + "/ws/fhir2/R4/Subscription/comm-module-" + org.getId())
                .header("Content-Type", "application/fhir+json")
                .header("Authorization", "Bearer " + org.getCallbackToken())
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block();

        log.info("FHIR Subscription registered for org={}", org.getName());
    }

    private Subscription buildSubscription(OrganisationConfig org, String callbackUrl) {
        Subscription sub = new Subscription();
        sub.setId("comm-module-" + org.getId());
        sub.setStatus(Subscription.SubscriptionStatus.REQUESTED);
        sub.setReason("OpenMRS Communication Module appointment notifications");
        sub.setCriteria("Appointment?");

        Subscription.SubscriptionChannelComponent channel = new Subscription.SubscriptionChannelComponent();
        channel.setType(Subscription.SubscriptionChannelType.RESTHOOK);
        channel.setEndpoint(callbackUrl);
        channel.setPayload("application/fhir+json");
        channel.addHeader("Authorization: Bearer " + org.getCallbackToken());
        sub.setChannel(channel);

        return sub;
    }
}
