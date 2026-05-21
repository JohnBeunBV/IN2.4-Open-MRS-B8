package nl.avans.communicatiemodule.provider;

import nl.avans.communicatiemodule.messaging.NotificationMessage;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class MessageTextBuilder {

    private MessageTextBuilder() {}

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm");

    /**
     * Builds a human-readable SMS/WhatsApp notification text.
     * Supports multi-charset: uses Unicode throughout (UTF-8).
     *
     * Includes:
     * - Patient name
     * - Date, time and appointment details (location, instructions)
     * - A unique cancellation link (requirement: patient can cancel via link)
     */
    public static String build(NotificationMessage msg) {
        ZoneId zone = safeZone(msg.getTimezone());
        ZonedDateTime localTime = msg.getAppointmentStart().atZone(zone);
        Locale locale = Locale.forLanguageTag(msg.getLanguage() != null ? msg.getLanguage() : "en");

        String formattedDate = localTime.format(FORMATTER.withLocale(locale));
        String recipientName = msg.getRecipientName() != null ? msg.getRecipientName() : "Patient";

        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(recipientName).append(",\n\n");
        sb.append("This is a reminder of your appointment on ").append(formattedDate).append(".\n");

        if (msg.getAppointmentDetails() != null && !msg.getAppointmentDetails().isBlank()) {
            sb.append("Details: ").append(msg.getAppointmentDetails()).append("\n");
        }

        // Cancellation link — requirement: patient can cancel via link in message
        if (msg.getCancellationUrl() != null && !msg.getCancellationUrl().isBlank()) {
            sb.append("\nTo cancel your appointment, click the link below:\n");
            sb.append(msg.getCancellationUrl()).append("\n");
        }

        return sb.toString();
    }

    private static ZoneId safeZone(String timezone) {
        try {
            return timezone != null ? ZoneId.of(timezone) : ZoneId.of("UTC");
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}