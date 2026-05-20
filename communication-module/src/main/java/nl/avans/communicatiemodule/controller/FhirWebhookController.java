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
 * Global token: enforced upstream by WebhookTokenFilter.
 * Per-org token: validated here with constant-time comparison.
 * TLS 1.3 is enforced at the reverse proxy level.
 */
@Slf4j
@RestController
@RequestMapping("/fhir/webhook")
@RequiredArgsConstructor
public class FhirWebhookController {

    private final FhirProcessorService fhirProcessorService;
    private final OrganisationConfigRepository organisationRepository;

    @PostMapping("/{organisationId}")
    public ResponseEntity<String> receive(
            @PathVariable UUID organisationId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody String fhirPayload) {

        log.debug("FHIR webhook received for org={}", organisationId);

        OrganisationConfig org = organisationRepository.findById(organisationId).orElse(null);
        if (org == null || !org.isActive()) {
            log.warn("Webhook received for unknown/inactive org={}", organisationId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Organisation not found");
        }

        // Per-org constant-time token check (global token already verified by WebhookTokenFilter)
        if (!isValidToken(authHeader, org.getCallbackToken())) {
            log.warn("Invalid per-org Bearer token for org={}", organisationId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            fhirProcessorService.processIncoming(organisationId, fhirPayload);
            return ResponseEntity.ok("ACK");
        } catch (IllegalArgumentException ex) {
            log.error("Invalid FHIR payload from org={}: {}", organisationId, ex.getMessage());
            // 400 NAK → OpenMRS knows payload is bad, no point retrying unchanged
            return ResponseEntity.badRequest().body("NAK: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Error processing FHIR webhook for org={}", organisationId, ex);
            // 500 → OpenMRS will retry (transient error)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing error");
        }
    }

    /**
     * Constant-time comparison to prevent timing-based token enumeration.
     */
    private boolean isValidToken(String authHeader, String expectedToken) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String received = authHeader.substring(7).trim();
        byte[] receivedBytes = received.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(receivedBytes, expectedBytes);
    }
}
