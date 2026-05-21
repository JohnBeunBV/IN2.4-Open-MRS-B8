package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.dto.OrganisationConfigDto;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganisationService {

    private final OrganisationConfigRepository repository;
    private final FhirSubscriptionService subscriptionService;
    private final HospitalInitializationService hospitalInitializationService;

    public List<OrganisationConfig> findAll() {
        return repository.findAll();
    }

    public OrganisationConfig findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organisation not found: " + id));
    }

    /**
     * Create a new hospital organisation.
     * Initializes encryption keys, Vault credentials, and FHIR subscriptions.
     */
    @Transactional
    public OrganisationConfig create(OrganisationConfigDto dto) {
        if (repository.existsByName(dto.getName())) {
            throw new IllegalStateException("Organisation with name '" + dto.getName() + "' already exists");
        }

        try {
            // Initialize hospital with encryption keys and Vault
            OrganisationConfig config = hospitalInitializationService.initializeHospital(dto);

            // Register FHIR Subscription on the OpenMRS instance
            subscriptionService.registerSubscription(config);
            log.info("Organisation created with encryption: id={}, name={}, provider={}",
                    config.getId(), config.getName(), config.getProviderType());
            return config;
        } catch (Exception e) {
            log.error("Failed to create organisation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize organisation: " + e.getMessage(), e);
        }
    }

    /**
     * Update hospital organisation configuration.
     * Can be used to change provider, timezone, language, etc.
     */
    @Transactional
    public OrganisationConfig update(UUID id, OrganisationConfigDto dto) {
        OrganisationConfig config = findById(id);
        applyDto(config, dto);
        config = repository.save(config);
        
        // Re-register FHIR subscription if OpenMRS URL changed
        if (dto.getOpenmrsBaseUrl() != null && !dto.getOpenmrsBaseUrl().equals(config.getOpenmrsBaseUrl())) {
            subscriptionService.registerSubscription(config);
        }
        
        log.info("Organisation updated: id={}, name={}", config.getId(), config.getName());
        return config;
    }

    /**
     * Deactivate a hospital organisation.
     * Stops new notifications; existing ones continue processing.
     */
    @Transactional
    public void deactivate(UUID id) {
        OrganisationConfig config = findById(id);
        config.setActive(false);
        repository.save(config);
        log.info("Organisation deactivated: id={}, name={}", id, config.getName());
    }

    /**
     * Rotate encryption keys for a hospital (maintenance operation).
     */
    @Transactional
    public void rotateEncryptionKeys(UUID id) throws Exception {
        log.info("Initiating encryption key rotation for organisation: {}", id);
        hospitalInitializationService.rotateEncryptionKey(id);
        log.info("Encryption key rotated successfully for organisation: {}", id);
    }

    private void applyDto(OrganisationConfig config, OrganisationConfigDto dto) {
        if (dto.getName() != null) {
            config.setName(dto.getName());
        }
        if (dto.getOpenmrsBaseUrl() != null) {
            config.setOpenmrsBaseUrl(dto.getOpenmrsBaseUrl());
        }
        if (dto.getProviderType() != null) {
            config.setProviderType(dto.getProviderType());
        }
        if (dto.getCallbackToken() != null) {
            config.setCallbackToken(dto.getCallbackToken());
        }
        if (dto.getTimezone() != null) {
            config.setTimezone(dto.getTimezone());
        }
        if (dto.getLanguage() != null) {
            config.setLanguage(dto.getLanguage());
        }
        if (dto.getVaultCredentialsPath() != null) {
            config.setVaultCredentialsPath(dto.getVaultCredentialsPath());
        }
    }
}
