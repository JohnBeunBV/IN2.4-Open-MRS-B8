package nl.avans.communicatiemodule.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import nl.avans.communicatiemodule.service.FhirProcessorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Receives FHIR Subscription notifications (REST Hook callbacks) from OpenMRS instances.
 *
 * Endpoint: POST /fhir/webhook/{organisationId}
 *
 * Authentication: Bearer token in Authorization header, validated per organisation.
 * TLS 1.3 is enforced at the reverse proxy / load balancer level.
 */
@Slf4j
@RestController
@RequestMapping("/fhir/webhook")
@RequiredArgsConstructor
public class FhirWebhookController {

    private final FhirProcessorService fhirProcessorService;
    private final OrganisationConfigRepository organisationRepository;

    /**
     * Receive a FHIR Subscription notification.
     * The organisation ID in the path ensures each hospital has its own endpoint.
     */
    @PostMapping("/{organisationId}")
    public ResponseEntity<String> receive(
            @PathVariable UUID organisationId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody String fhirPayload) {

        log.debug("FHIR webhook received for org={}", organisationId);

        // Authenticate: validate the Bearer token against the stored callback token
        OrganisationConfig org = organisationRepository.findById(organisationId).orElse(null);
        if (org == null || !org.isActive()) {
            log.warn("Webhook received for unknown/inactive org={}", organisationId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Organisation not found");
        }

        if (!isValidToken(authHeader, org.getCallbackToken())) {
            log.warn("Invalid Bearer token for org={}", organisationId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            fhirProcessorService.processIncoming(organisationId, fhirPayload);
            // HL7 FHIR: respond with 200 OK to acknowledge receipt (ACK)
            return ResponseEntity.ok("ACK");
        } catch (IllegalArgumentException ex) {
            log.error("Invalid FHIR payload from org={}: {}", organisationId, ex.getMessage());
            // HL7 ACK: NAK - return 400 so OpenMRS knows to retry with a correct payload
            return ResponseEntity.badRequest().body("NAK: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Error processing FHIR webhook for org={}", organisationId, ex);
            // Return 500 so the FHIR Subscription knows to retry
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing error");
        }
    }

    /** Constant-time comparison prevents timing-based token enumeration. */
    private boolean isValidToken(String authHeader, String expectedToken) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        byte[] received = authHeader.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(received, expected);
    }
}
