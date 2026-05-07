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

    // Recipient details
    private String recipientPhone;
    private String recipientName;

    // Content
    private String appointmentLocation;
    private Instant appointmentStart;
    private String appointmentInstructions;
    private String language;
    private String timezone;

    /** Current retry attempt (0 = first try) */
    private int retryCount = 0;
}
