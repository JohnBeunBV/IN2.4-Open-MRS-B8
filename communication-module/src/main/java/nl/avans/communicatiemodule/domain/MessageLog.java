package nl.avans.communicatiemodule.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit log of sent messages.
 * Contains NO direct patient data or appointment details.
 * Retained for 1 year to allow invoice verification with messaging providers.
 */
@Entity
@Table(name = "message_log",
       indexes = {
           @Index(name = "idx_log_expires", columnList = "expires_at"),
           @Index(name = "idx_log_org",     columnList = "organisation_id")
       })
@Data
@NoArgsConstructor
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Reference back to the organisation - no PII */
    @Column(name = "organisation_id", nullable = false)
    private UUID organisationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private ProviderType providerType;

    /**
     * Opaque reference returned by the provider (e.g. message ID).
     * Allows cross-referencing with provider invoices.
     */
    @Column(name = "provider_message_ref")
    private String providerMessageRef;

    /** Type of notification: "REMINDER_24H" or "REMINDER_1H" */
    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status = MessageStatus.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "provider_response", columnDefinition = "TEXT")
    private String providerResponse;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Automatically purged 1 year after creation */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public enum MessageStatus {
        PENDING, SENT, FAILED, RETRYING
    }

    @PrePersist
    void onPersist() {
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(365L * 24 * 3600);
        }
    }
}
