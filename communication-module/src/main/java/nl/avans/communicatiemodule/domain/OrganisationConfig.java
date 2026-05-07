package nl.avans.communicatiemodule.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Configuration record for one OpenMRS organisation (hospital).
 * Credentials are stored as Vault references (path strings), not raw values.
 */
@Entity
@Table(name = "organisation_config")
@Data
@NoArgsConstructor
public class OrganisationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Base URL of the OpenMRS instance, e.g. https://hospital-a.openmrs.org */
    @Column(name = "openmrs_base_url", nullable = false)
    private String openmrsBaseUrl;

    /** Which messaging provider this organisation uses */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private ProviderType providerType;

    /**
     * Vault path where provider credentials are stored.
     * Example: "secret/communicatiemodule/org-abc/provider-credentials"
     * The actual key/password is never stored here.
     */
    @Column(name = "vault_credentials_path")
    private String vaultCredentialsPath;

    /**
     * Bearer token used to authenticate inbound FHIR Subscription callbacks.
     * Stored encrypted (AES-256). The encryption is handled at the service layer.
     */
    @Column(name = "callback_token", nullable = false)
    private String callbackToken;

    /** IANA timezone id, e.g. "Europe/Amsterdam" */
    @Column(nullable = false)
    private String timezone = "UTC";

    /** ISO 639-1 language code for notification messages */
    @Column(nullable = false)
    private String language = "en";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
