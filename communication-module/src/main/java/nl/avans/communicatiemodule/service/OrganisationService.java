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

    public List<OrganisationConfig> findAll() {
        return repository.findAll();
    }

    public OrganisationConfig findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organisation not found: " + id));
    }

    @Transactional
    public OrganisationConfig create(OrganisationConfigDto dto) {
        if (repository.existsByName(dto.getName())) {
            throw new IllegalStateException("Organisation with name '" + dto.getName() + "' already exists");
        }

        OrganisationConfig config = new OrganisationConfig();
        applyDto(config, dto);
        config = repository.save(config);

        try {
            subscriptionService.registerSubscription(config);
        } catch (Exception e) {
            log.warn("FHIR subscription registratie mislukt voor org={}: {} (poller gebruikt als fallback)",
                    config.getName(), e.getMessage());
        }

        log.info("Organisation created: id={}, name={}", config.getId(), config.getName());
        return config;
    }

    @Transactional
    public OrganisationConfig update(UUID id, OrganisationConfigDto dto) {
        OrganisationConfig config = findById(id);
        applyDto(config, dto);
        config = repository.save(config);

        try {
            subscriptionService.registerSubscription(config);
        } catch (Exception e) {
            log.warn("FHIR subscription update mislukt voor org={}: {} (poller gebruikt als fallback)",
                    config.getName(), e.getMessage());
        }

        return config;
    }

    @Transactional
    public void deactivate(UUID id) {
        OrganisationConfig config = findById(id);
        config.setActive(false);
        repository.save(config);
        log.info("Organisation deactivated: id={}", id);
    }

    private void applyDto(OrganisationConfig config, OrganisationConfigDto dto) {
        config.setName(dto.getName());
        config.setOpenmrsBaseUrl(dto.getOpenmrsBaseUrl());
        config.setOpenmrsUsername(dto.getOpenmrsUsername() != null ? dto.getOpenmrsUsername() : "admin");
        config.setOpenmrsPassword(dto.getOpenmrsPassword() != null ? dto.getOpenmrsPassword() : "Admin1234");
        config.setProviderType(dto.getProviderType());
        config.setCallbackToken(dto.getCallbackToken());
        config.setTimezone(dto.getTimezone() != null ? dto.getTimezone() : "UTC");
        config.setLanguage(dto.getLanguage() != null ? dto.getLanguage() : "en");
        if (dto.getVaultCredentialsPath() != null) {
            config.setVaultCredentialsPath(dto.getVaultCredentialsPath());
        }
    }
}