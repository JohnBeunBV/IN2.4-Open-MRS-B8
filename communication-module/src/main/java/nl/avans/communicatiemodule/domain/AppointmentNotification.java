package nl.avans.communicatiemodule.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import nl.avans.communicatiemodule.security.EncryptedStringConverter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a single appointment's notification schedule.
 *
 * Patient contact fields (patientPhone, patientName) are encrypted at rest using
 * AES-256-GCM via EncryptedStringConverter and are PURGED 14 days after the
 * appointment as required by AVG/GDPR.
 */
@Entity
@Table(name = "appointment_notification",
       indexes = {
           @Index(name = "idx_notify_at_24h", columnList = "notify_at_24h"),
           @Index(name = "idx_notify_at_1h",  columnList = "notify_at_1h"),
           @Index(name = "idx_expires_at",    columnList = "expires_at"),
           @Index(name = "idx_org_id",        columnList = "organisation_id")
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

    // ── Patient contact — ENCRYPTED AT REST, PURGED after 14 days ──────────

    /**
     * Stored as AES-256-GCM ciphertext. Null after GDPR purge.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "patient_phone", nullable = false, length = 512)
    private String patientPhone;

    /**
     * Stored as AES-256-GCM ciphertext. Null after GDPR purge.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "patient_name", length = 512)
    private String patientName;

    // ── Appointment details ─────────────────────────────────────────────────

    @Column(name = "appointment_start", nullable = false)
    private Instant appointmentStart;

    @Column(name = "appointment_location")
    private String appointmentLocation;

    @Column(name = "appointment_instructions", columnDefinition = "TEXT")
    private String appointmentInstructions;

    // ── Notification schedule ───────────────────────────────────────────────

    @Column(name = "notify_at_24h")
    private Instant notifyAt24h;

    @Column(name = "notify_at_1h")
    private Instant notifyAt1h;

    @Column(name = "sent_24h", nullable = false)
    private boolean sent24h = false;

    @Column(name = "sent_1h", nullable = false)
    private boolean sent1h = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * GDPR purge timestamp: 14 days after appointment start.
     * A scheduled job sets patient_phone/name to NULL after this date.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
