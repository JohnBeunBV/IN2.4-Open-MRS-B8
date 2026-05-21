package nl.avans.communicatiemodule.messaging;

import nl.avans.communicatiemodule.domain.ProviderType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Message placed on the RabbitMQ notification queue.
 * Contains everything needed to send one notification without further DB lookups.
 *
 * IMPORTANT: all patient fields (recipientPhone, recipientName, appointmentDetails)
 * must be DECRYPTED before constructing this object. The queue message is encrypted
 * in transit by RabbitMQ TLS; it must never carry raw AES ciphertext.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    private UUID notificationId;
    private UUID organisationId;
    private ProviderType providerType;

    /** "REMINDER_24H" or "REMINDER_1H" */
    private String notificationType;

    // ── Recipient (decrypted) ────────────────────────────────────────────────
    /** Decrypted patient phone number or e-mail address, e.g. "+31612345678" */
    private String recipientPhone;

    /** Decrypted patient display name, e.g. "Jan de Vries" */
    private String recipientName;

    // ── Appointment ──────────────────────────────────────────────────────────
    /**
     * Full cancellation URL for this appointment, e.g.
     * "https://comm.hospital.nl/cancel/org-uuid/token-uuid"
     * Included as a clickable link in the notification body.
     */
    private String cancellationUrl;

    /** Appointment start time in UTC; converted to org timezone by MessageTextBuilder. */
    private Instant appointmentStart;

    /**
     * Decrypted combined appointment details, e.g.
     * "15 juni 2025, 10:00 uur, Polikliniek B Kamer 12, Nuchter komen"
     * Formatted by FhirProcessorService and stored encrypted in AppointmentNotification.
     */
    private String appointmentDetails;

    /** ISO 639-1 language code, e.g. "nl" or "en" */
    private String language;

    /** IANA timezone id, e.g. "Europe/Amsterdam" */
    private String timezone;

    /** Current retry attempt (0 = first try) */
    private int retryCount = 0;
}