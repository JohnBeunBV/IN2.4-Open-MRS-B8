package nl.avans.communicatiemodule.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks a single appointment's notification schedule.
 * 
 * Encryption & GDPR:
 * - Patient contact (phone/email) and appointment details are stored ENCRYPTED (AES-256)
 * - Unencrypted data is PURGED 14 days after appointment starts
 * - Audit trail (message logs) retained for 1 year for compliance
 * 
 * Cancellation:
 * - Unique cancellation token per appointment allows patients to cancel via link
 * - Token is opaque and unguessable (UUID-based)
 * - Cancellation updates appointment status in OpenMRS via FHIR API
 */
@Entity
@Table(name = "appointment_notification",
       indexes = {
           @Index(name = "idx_notify_at_24h", columnList = "notify_at_24h"),
           @Index(name = "idx_notify_at_1h",  columnList = "notify_at_1h"),
           @Index(name = "idx_expires_at",    columnList = "expires_at"),
           @Index(name = "idx_org_id",        columnList = "organisation_id"),
           @Index(name = "idx_cancel_token",  columnList = "cancellation_token"),
           @Index(name = "idx_fhir_id",       columnList = "fhir_appointment_id")
       })
@Data
@NoArgsConstructor
public class AppointmentNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FHIR Appointment resource ID from OpenMRS */
    @Column(name = "fhir_appointment_id", nullable = false)
    private String fhirAppointmentId;

    @Column(name = "organisation_id", nullable = false)
    private UUID organisationId;

    // ── Encrypted Patient Contact (AES-256, PURGED after 14 days) ───────────
    /**
     * Patient phone number or email, encrypted with AES-256
     * Format: BASE64(AES-256-GCM(plaintext))
     * Examples: "+31612345678" or "patient@example.com"
     */
    @Column(name = "patient_contact_encrypted", columnDefinition = "TEXT")
    private String patientContactEncrypted;

    /**
     * Patient name, encrypted with AES-256
     * Examples: "Jan de Vries", "María García"
     */
    @Column(name = "patient_name_encrypted", columnDefinition = "TEXT")
    private String patientNameEncrypted;

    // ── Encrypted Appointment Details (AES-256, PURGED after 14 days) ───────
    /**
     * Full appointment details encrypted: date, time, location, instructions
     * Example: "15 juni 2025, 10:00-10:30 uur, Polikliniek B Kamer 12, Nuchter komen"
     */
    @Column(name = "appointment_details_encrypted", columnDefinition = "TEXT")
    private String appointmentDetailsEncrypted;

    /**
     * Appointment start time in UTC (unencrypted, for scheduling)
     * Converted to organisation's timezone for display
     */
    @Column(name = "appointment_start", nullable = false)
    private LocalDateTime appointmentStartUtc;

    // ── Notification Schedule ────────────────────────────────────────────────
    @Column(name = "notify_at_24h")
    private Instant notifyAt24h;

    @Column(name = "notify_at_1h")
    private Instant notifyAt1h;

    @Column(name = "sent_24h", nullable = false)
    private boolean sent24h = false;

    @Column(name = "sent_1h", nullable = false)
    private boolean sent1h = false;

    // ── Appointment Cancellation ──────────────────────────────────────────────
    /**
     * Unique, opaque token for patient to cancel appointment.
     * Patient clicks link: https://notification-service/cancel/{organisationId}/{cancellationToken}
     * This token is never sent to external systems, only in the message content.
     */
    @Column(name = "cancellation_token", nullable = false, unique = true)
    private String cancellationToken = UUID.randomUUID().toString();

    /**
     * Whether patient used the cancellation link
     */
    @Column(name = "cancelled_by_patient", nullable = false)
    private boolean cancelledByPatient = false;

    /**
     * Timestamp when patient cancelled (if applicable)
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // ── Provider & Status ──────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type")
    private ProviderType providerType;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error")
    private String lastError;

    // ── Audit Trail ────────────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * GDPR purge timestamp: 14 days after appointment creation.
     * A scheduled job purges patient_contact_encrypted and patient_name_encrypted after this date.
     * Message logs retained separately for 1 year (see MessageLog.expiresAt).
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onPersist() {
        if (expiresAt == null) {
            // Retain encrypted patient data for 14 days, then purge
            expiresAt = createdAt.plusSeconds(14L * 24 * 3600);
        }
        if (cancellationToken == null) {
            cancellationToken = UUID.randomUUID().toString();
        }
    }
}
