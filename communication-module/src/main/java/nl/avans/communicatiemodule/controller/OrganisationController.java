package nl.avans.communicatiemodule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.MessageLog;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.dto.OrganisationConfigDto;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import nl.avans.communicatiemodule.service.OrganisationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * REST API for managing hospital organizations and their communication provider preferences.
 * 
 * Hospital Flow:
 * 1. Hospital registers via POST /api/organisations
 *    - Provide: name, OpenMRS URL, preferred communication provider
 * 2. Hospital can change provider via PUT /api/organisations/{id}/provider
 * 3. System automatically sends notifications using selected provider
 * 4. Hospitals can monitor message logs and delivery status
 */
@Slf4j
@RestController
@RequestMapping("/api/organisations")
@RequiredArgsConstructor
public class OrganisationController {

    private final OrganisationService organisationService;
    private final MessageLogRepository messageLogRepository;

    /**
     * List all registered hospitals/organisations.
     */
    @GetMapping
    public List<OrganisationConfig> listAll() {
        return organisationService.findAll();
    }

    /**
     * Get details for a specific hospital/organisation.
     */
    @GetMapping("/{id}")
    public OrganisationConfig getById(@PathVariable UUID id) {
        return organisationService.findById(id);
    }

    /**
     * Register a new hospital/organisation.
     * 
     * Request:
     * {
     *   "name": "Ziekenhuis Utrecht",
     *   "openmrsBaseUrl": "https://openmrs.example.com",
     *   "providerType": "SWIFT_SEND",  // or: SECURE_POST, LEGACY_LINK, ASYNC_FLOW
     *   "callbackToken": "generated-token-123",
     *   "timezone": "Europe/Amsterdam",
     *   "language": "nl"
     * }
     */
    @PostMapping
    public ResponseEntity<OrganisationConfig> create(@Valid @RequestBody OrganisationConfigDto dto) {
        log.info("Creating new organisation: {}", dto.getName());
        OrganisationConfig created = organisationService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update hospital/organisation configuration.
     * Can be used to change timezone, language, or other settings.
     */
    @PutMapping("/{id}")
    public OrganisationConfig update(@PathVariable UUID id,
                                     @Valid @RequestBody OrganisationConfigDto dto) {
        log.info("Updating organisation: {}", id);
        return organisationService.update(id, dto);
    }

    /**
     * Change the communication provider for a hospital.
     * 
     * Hospitals can switch providers at any time.
     * Existing pending notifications continue with the current provider.
     * Only new notifications use the new provider.
     * 
     * Request:
     * {
     *   "providerType": "SECURE_POST"
     * }
     */
    @PutMapping("/{id}/provider")
    public ResponseEntity<ProviderChangeResponse> changeProvider(
            @PathVariable UUID id,
            @RequestBody ProviderChangeRequest request) {
        
        if (request.getProviderType() == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            OrganisationConfig org = organisationService.findById(id);
            ProviderType oldProvider = org.getProviderType();
            
            OrganisationConfigDto dto = new OrganisationConfigDto();
            dto.setProviderType(request.getProviderType());
            
            OrganisationConfig updated = organisationService.update(id, dto);
            
            log.info("Provider changed for organisation {}: {} -> {}", 
                    id, oldProvider, request.getProviderType());
            
            return ResponseEntity.ok(ProviderChangeResponse.builder()
                    .organisationId(id)
                    .previousProvider(oldProvider)
                    .newProvider(request.getProviderType())
                    .message("Communication provider updated successfully. " +
                            "New notifications will use " + request.getProviderType() + ".")
                    .build());
        } catch (Exception e) {
            log.error("Failed to change provider for organisation {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Deactivate a hospital/organisation.
     * Stops all new notifications; existing messages continue processing.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        log.info("Deactivating organisation: {}", id);
        organisationService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get available communication providers.
     * Hospital can use this to see which providers are available for selection.
     */
    @GetMapping("/providers/available")
    public ResponseEntity<List<ProviderInfo>> getAvailableProviders() {
        List<ProviderInfo> providers = Arrays.stream(ProviderType.values())
                .map(p -> new ProviderInfo(
                        p.name(),
                        getProviderDescription(p),
                        getProviderCapabilities(p)
                ))
                .toList();
        return ResponseEntity.ok(providers);
    }

    /**
     * Get message logs for a hospital (for monitoring dashboard).
     * Shows delivery status, retry attempts, provider responses.
     */
    @GetMapping("/{id}/logs")
    public List<MessageLog> getLogs(@PathVariable UUID id) {
        return messageLogRepository.findByOrganisationId(id);
    }

    /**
     * Get statistics for a hospital.
     * Shows total notifications sent, success rate, failures, etc.
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<OrganisationStatistics> getStatistics(@PathVariable UUID id) {
        long totalMessages = messageLogRepository.countByOrganisationId(id);
        long sentMessages = messageLogRepository.countByOrganisationIdAndStatus(id, MessageLog.MessageStatus.SENT);
        long failedMessages = messageLogRepository.countByOrganisationIdAndStatus(id, MessageLog.MessageStatus.FAILED);
        
        return ResponseEntity.ok(OrganisationStatistics.builder()
                .organisationId(id)
                .totalNotifications(totalMessages)
                .successfulNotifications(sentMessages)
                .failedNotifications(failedMessages)
                .successRate(totalMessages > 0 ? (100.0 * sentMessages / totalMessages) : 0)
                .build());
    }

    // ── Helper Methods ────────────────────────────────────────────────────

    private String getProviderDescription(ProviderType provider) {
        return switch (provider) {
            case SWIFT_SEND -> "SwiftSend - High-speed SMS delivery";
            case SECURE_POST -> "SecurePost - Secure postal notifications";
            case LEGACY_LINK -> "LegacyLink - Integration with legacy systems";
            case ASYNC_FLOW -> "AsyncFlow - Asynchronous message processing";
        };
    }

    private String[] getProviderCapabilities(ProviderType provider) {
        return switch (provider) {
            case SWIFT_SEND -> new String[]{"SMS", "MMS", "WhatsApp", "24/7 delivery"};
            case SECURE_POST -> new String[]{"Encrypted mail", "Registered delivery", "GDPR compliant"};
            case LEGACY_LINK -> new String[]{"Fax", "Phone", "Email", "Legacy system support"};
            case ASYNC_FLOW -> new String[]{"Queued processing", "Retry logic", "Multi-channel support"};
        };
    }

    // ── Response DTOs ────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderChangeRequest {
        private ProviderType providerType;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderChangeResponse {
        private UUID organisationId;
        private ProviderType previousProvider;
        private ProviderType newProvider;
        private String message;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderInfo {
        private String name;
        private String description;
        private String[] capabilities;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrganisationStatistics {
        private UUID organisationId;
        private long totalNotifications;
        private long successfulNotifications;
        private long failedNotifications;
        private double successRate;
    }
}
