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

/**
 * Registers FHIR Subscription resources on each active OpenMRS instance.
 * Runs once at startup and can be triggered manually via the admin API.
 *
 * The subscription tells OpenMRS: "when an Appointment changes, POST the resource
 * to our callback URL with a Bearer token for authentication."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FhirSubscriptionService {

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
        activeOrgs.forEach(this::registerSubscription);
    }

    /**
     * Register (or re-register) the FHIR Subscription for a single organisation.
     */
    public void registerSubscription(OrganisationConfig org) {
        String callbackUrl = callbackBaseUrl + callbackPath + "/" + org.getId();
        Subscription subscription = buildSubscription(org, callbackUrl);
        String payload = fhirContext.newJsonParser().encodeResourceToString(subscription);

        log.info("Registering FHIR Subscription for org={} at {}", org.getName(), org.getOpenmrsBaseUrl());

        try {
            webClient.put()
                    .uri(org.getOpenmrsBaseUrl() + "/ws/fhir2/R4/Subscription/comm-module-" + org.getId())
                    .header("Content-Type", "application/fhir+json")
                    .header("Authorization", "Bearer " + org.getCallbackToken())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("FHIR Subscription registered for org={}", org.getName());
        } catch (Exception ex) {
            // Non-fatal: subscription registration may fail if OpenMRS is not yet reachable
            log.warn("Could not register FHIR Subscription for org={}: {}", org.getName(), ex.getMessage());
        }
    }

    private Subscription buildSubscription(OrganisationConfig org, String callbackUrl) {
        Subscription sub = new Subscription();
        sub.setId("comm-module-" + org.getId());
        sub.setStatus(Subscription.SubscriptionStatus.REQUESTED);
        sub.setReason("OpenMRS Communication Module appointment notifications");

        // Criteria: notify on any Appointment resource change
        sub.setCriteria("Appointment?");

        // Channel: REST Hook (webhook) over HTTPS with Bearer token
        Subscription.SubscriptionChannelComponent channel = new Subscription.SubscriptionChannelComponent();
        channel.setType(Subscription.SubscriptionChannelType.RESTHOOK);
        channel.setEndpoint(callbackUrl);
        channel.setPayload("application/fhir+json");
        channel.addHeader("Authorization: Bearer " + org.getCallbackToken());
        sub.setChannel(channel);

        return sub;
    }
}
