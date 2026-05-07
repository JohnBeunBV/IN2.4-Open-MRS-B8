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
     * Supports multi-charset: uses Unicode throughout.
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

        if (msg.getAppointmentLocation() != null && !msg.getAppointmentLocation().isBlank()) {
            sb.append("Location: ").append(msg.getAppointmentLocation()).append("\n");
        }

        if (msg.getAppointmentInstructions() != null && !msg.getAppointmentInstructions().isBlank()) {
            sb.append("Instructions: ").append(msg.getAppointmentInstructions()).append("\n");
        }

        sb.append("\nTo cancel your appointment, please contact us.");
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
