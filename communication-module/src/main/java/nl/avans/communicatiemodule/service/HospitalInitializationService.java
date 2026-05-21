package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.dto.OrganisationConfigDto;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import nl.avans.communicatiemodule.security.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.vault.core.VaultTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hospital onboarding and initialization service.
 * 
 * Responsibilities:
 * - Create organisation with encryption keys
 * - Generate and store Vault credentials
 * - Set up provider-specific configurations
 * - Initialize RabbitMQ queues per provider
 * - Configure Vault policies per organisation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalInitializationService {

    private final OrganisationConfigRepository organisationRepository;
    private final EncryptionService encryptionService;
    private final VaultTemplate vaultTemplate;

    /**
     * Initialize a new hospital in the system.
     * 
     * This process:
     * 1. Create organisation in database
     * 2. Generate master encryption key in Vault
     * 3. Store provider credentials in Vault
     * 4. Generate unique callback token
     * 5. Return organisation config
     */
    @Transactional
    public OrganisationConfig initializeHospital(OrganisationConfigDto dto) throws Exception {
        log.info("Initializing new hospital: {}", dto.getName());

        // Generate unique IDs and tokens
        UUID organisationId = UUID.randomUUID();
        String vaultPath = "secret/communicatiemodule/organisations/" + organisationId;

        try {
            // Step 1: Generate master encryption key in Vault
            String keyPath = vaultPath + "/master-key";
            encryptionService.generateAndStoreKey(keyPath);
            log.info("Generated master encryption key in Vault: {}", keyPath);

            // Step 2: Store provider credentials in Vault
            storeProviderCredentials(vaultPath, dto);

            // Step 3: Create organisation in database
            OrganisationConfig org = new OrganisationConfig();
            org.setId(organisationId);
            org.setName(dto.getName());
            org.setOpenmrsBaseUrl(dto.getOpenmrsBaseUrl());
            org.setProviderType(dto.getProviderType());
            org.setTimezone(dto.getTimezone() != null ? dto.getTimezone() : "UTC");
            org.setLanguage(dto.getLanguage() != null ? dto.getLanguage() : "en");
            org.setCallbackToken(dto.getCallbackToken() != null ? dto.getCallbackToken() : generateCallbackToken());
            org.setVaultCredentialsPath(vaultPath);
            org.setActive(true);
            org.setCreatedAt(Instant.now());
            org.setUpdatedAt(Instant.now());

            OrganisationConfig saved = organisationRepository.save(org);
            log.info("Hospital initialized successfully: id={}, name={}, provider={}",
                    organisationId, dto.getName(), dto.getProviderType());

            // Log initialization event
            logInitialisationEvent(organisationId, dto.getName(), "success");

            return saved;

        } catch (Exception e) {
            log.error("Failed to initialize hospital: {}", e.getMessage(), e);
            // Clean up partial state
            cleanupFailedInitialisation(vaultPath, organisationId);
            logInitialisationEvent(organisationId, dto.getName(), "failed");
            throw e;
        }
    }

    /**
     * Store provider-specific credentials in Vault.
     * Different providers may have different credential types.
     */
    private void storeProviderCredentials(String vaultPath, OrganisationConfigDto dto) throws Exception {
        String credentialsPath = vaultPath + "/provider-credentials";

        Map<String, String> credentials = new HashMap<>();
        credentials.put("provider_type", dto.getProviderType().name());
        credentials.put("timestamp", Instant.now().toString());

        // Provider-specific credentials (from environment or request)
        switch (dto.getProviderType()) {
            case SWIFT_SEND:
                credentials.put("api_key", System.getenv("SWIFTSEND_API_KEY"));
                break;
            case SECURE_POST:
                credentials.put("username", System.getenv("SECUREPOST_USERNAME"));
                credentials.put("password", System.getenv("SECUREPOST_PASSWORD"));
                break;
            case LEGACY_LINK:
                credentials.put("username", System.getenv("LEGACYLINK_USERNAME"));
                credentials.put("password", System.getenv("LEGACYLINK_PASSWORD"));
                break;
            case ASYNC_FLOW:
                credentials.put("api_key", System.getenv("ASYNCFLOW_API_KEY"));
                break;
        }

        vaultTemplate.write(credentialsPath, credentials);
        log.info("Stored provider credentials in Vault: {}", credentialsPath);
    }

    /**
     * Generate a unique, cryptographically secure callback token.
     * Used for FHIR Subscription webhook authentication.
     */
    private String generateCallbackToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    /**
     * Clean up on initialization failure.
     * Removes Vault entries that were created.
     */
    private void cleanupFailedInitialisation(String vaultPath, UUID organisationId) {
        try {
            vaultTemplate.delete(vaultPath);
            log.warn("Cleaned up Vault entries for failed initialisation: {}", vaultPath);
        } catch (Exception e) {
            log.error("Failed to cleanup Vault on initialisation failure: {}", e.getMessage());
        }
    }

    /**
     * Log hospital initialisation event for audit trail.
     */
    private void logInitialisationEvent(UUID organisationId, String hospitalName, String status) {
        log.info("Hospital initialisation event: id={}, name={}, status={}, timestamp={}",
                organisationId, hospitalName, status, Instant.now());
        // Could be extended to write to audit_log table
    }

    /**
     * Rotate encryption keys for a hospital (maintenance task).
     * Should be called periodically (every 90 days) or on-demand.
     */
    @Transactional
    public void rotateEncryptionKey(UUID organisationId) throws Exception {
        log.info("Rotating encryption key for organisation: {}", organisationId);

        OrganisationConfig org = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new IllegalArgumentException("Organisation not found: " + organisationId));

        String vaultPath = org.getVaultCredentialsPath();
        String oldKeyPath = vaultPath + "/master-key";
        String newKeyPath = vaultPath + "/master-key-backup-" + Instant.now().getEpochSecond();

        try {
            // Backup old key
            Object oldKey = vaultTemplate.read(oldKeyPath);
            vaultTemplate.write(newKeyPath, oldKey);

            // Generate new key
            encryptionService.generateAndStoreKey(oldKeyPath);

            log.info("Encryption key rotated for organisation {}: backup at {}", organisationId, newKeyPath);

        } catch (Exception e) {
            log.error("Failed to rotate encryption key for organisation {}: {}", organisationId, e.getMessage());
            throw e;
        }
    }
}
