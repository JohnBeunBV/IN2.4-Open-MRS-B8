package nl.avans.communicatiemodule.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import nl.avans.communicatiemodule.security.EncryptionService;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FhirProcessorService {

    private final FhirContext fhirContext;
    private final AppointmentNotificationRepository notificationRepository;
    private final OrganisationConfigRepository organisationRepository;
    private final EncryptionService encryptionService;

    /**
     * Processes an incoming FHIR Bundle (Subscription notification) or raw Appointment JSON.
     *
     * @param organisationId  the organisation this webhook belongs to
     * @param fhirPayload     raw JSON payload from the FHIR Subscription notification
     */
    @Transactional
    public void processIncoming(UUID organisationId, String fhirPayload) {
        log.debug("Processing FHIR payload for org={}", organisationId);

        IParser parser = fhirContext.newJsonParser();

        // The payload may be a Bundle or a raw Appointment
        try {
            Resource resource = (Resource) parser.parseResource(fhirPayload);

            if (resource instanceof Bundle bundle) {
                bundle.getEntry().forEach(entry -> {
                    if (entry.getResource() instanceof Appointment appointment) {
                        processAppointment(organisationId, appointment);
                    }
                });
            } else if (resource instanceof Appointment appointment) {
                processAppointment(organisationId, appointment);
            } else {
                log.warn("Received unexpected FHIR resource type: {}", resource.getResourceType());
            }
        } catch (Exception ex) {
            log.error("Failed to parse FHIR payload for org={}: {}", organisationId, ex.getMessage());
            throw new IllegalArgumentException("Invalid FHIR payload: " + ex.getMessage(), ex);
        }
    }

    private void processAppointment(UUID organisationId, Appointment appointment) {
        String fhirId = appointment.getIdElement().getIdPart();
        log.info("Processing FHIR Appointment: id={}, status={}", fhirId, appointment.getStatus());

        // Handle cancellation
        if (appointment.getStatus() == Appointment.AppointmentStatus.CANCELLED
                || appointment.getStatus() == Appointment.AppointmentStatus.NOSHOW) {
            cancelNotification(fhirId);
            return;
        }

        // Skip appointments already started or finished
        if (appointment.getStatus() == Appointment.AppointmentStatus.FULFILLED
                || appointment.getStatus() == Appointment.AppointmentStatus.ENTEREDINERROR) {
            log.debug("Skipping appointment {} with status {}", fhirId, appointment.getStatus());
            return;
        }

        Instant appointmentStart = extractStart(appointment);
        if (appointmentStart == null) {
            log.warn("Appointment {} has no start time, skipping", fhirId);
            return;
        }

        // Don't schedule notifications for past appointments
        if (appointmentStart.isBefore(Instant.now())) {
            log.info("Appointment {} is in the past, skipping", fhirId);
            return;
        }

        OrganisationConfig org = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new IllegalArgumentException("Organisation not found: " + organisationId));

        // Upsert: update existing or create new
        AppointmentNotification notification = notificationRepository
                .findByFhirAppointmentId(fhirId)
                .orElseGet(AppointmentNotification::new);

        notification.setFhirAppointmentId(fhirId);
        notification.setOrganisationId(organisationId);

        // Convert appointment start to UTC as LocalDateTime
        LocalDateTime startUtc = LocalDateTime.ofInstant(appointmentStart, ZoneId.of("UTC"));
        notification.setAppointmentStartUtc(startUtc);

        // Extract and ENCRYPT patient contact
        try {
            String patientPhone = extractPhone(appointment);
            String encryptedPhone = encryptionService.encrypt(patientPhone, 
                    org.getVaultCredentialsPath() + "/patient-contact");
            notification.setPatientContactEncrypted(encryptedPhone);
        } catch (Exception e) {
            log.error("Failed to encrypt patient contact: {}", e.getMessage());
            return;
        }

        // Extract and ENCRYPT patient name
        try {
            String patientName = extractPatientName(appointment);
            if (patientName != null) {
                String encryptedName = encryptionService.encrypt(patientName,
                        org.getVaultCredentialsPath() + "/patient-name");
                notification.setPatientNameEncrypted(encryptedName);
            }
        } catch (Exception e) {
            log.warn("Failed to encrypt patient name: {}", e.getMessage());
        }

        // Extract and ENCRYPT appointment details (location + instructions)
        try {
            String location = extractLocation(appointment);
            String instructions = extractInstructions(appointment);
            ZoneId zone = safeZone(org.getTimezone());
            
            // Format appointment details with local timezone
            String appointmentDetails = formatAppointmentDetails(
                    appointmentStart, zone, location, instructions);
            
            String encryptedDetails = encryptionService.encrypt(appointmentDetails,
                    org.getVaultCredentialsPath() + "/appointment-details");
            notification.setAppointmentDetailsEncrypted(encryptedDetails);
        } catch (Exception e) {
            log.error("Failed to encrypt appointment details: {}", e.getMessage());
            return;
        }

        // Schedule notification times in org's timezone
        ZoneId zone = safeZone(org.getTimezone());
        notification.setNotifyAt24h(appointmentStart.minus(24, ChronoUnit.HOURS));
        notification.setNotifyAt1h(appointmentStart.minus(1, ChronoUnit.HOURS));

        // If it's already past the 24h mark, skip that notification
        if (notification.getNotifyAt24h().isBefore(Instant.now())) {
            notification.setSent24h(true);
        }

        if (notification.getStatus() == null || notification.getStatus() == NotificationStatus.FAILED) {
            notification.setStatus(NotificationStatus.PENDING);
        }

        notificationRepository.save(notification);
        log.info("Appointment notification saved (encrypted): fhirId={}, notify24h={}, notify1h={}",
                 fhirId, notification.getNotifyAt24h(), notification.getNotifyAt1h());
    }

    /**
     * Format appointment details with local timezone for display.
     * Example output: "15 juni 2025, 10:00-10:30 uur, Polikliniek B Kamer 12, Nuchter komen"
     */
    private String formatAppointmentDetails(Instant appointmentStart, ZoneId zone, 
                                           String location, String instructions) {
        LocalDateTime localTime = LocalDateTime.ofInstant(appointmentStart, zone);
        StringBuilder sb = new StringBuilder();
        sb.append(formatDateLocalized(localTime))
                .append(", ")
                .append(String.format("%02d:%02d uur", localTime.getHour(), localTime.getMinute()));
        
        if (location != null && !location.isBlank()) {
            sb.append(", ").append(location);
        }
        if (instructions != null && !instructions.isBlank()) {
            sb.append(", ").append(instructions);
        }
        
        return sb.toString();
    }

    private String formatDateLocalized(LocalDateTime dateTime) {
        // Basic Dutch date formatting: "15 juni 2025"
        String[] months = {"januari", "februari", "maart", "april", "mei", "juni",
                          "juli", "augustus", "september", "oktober", "november", "december"};
        return dateTime.getDayOfMonth() + " " + months[dateTime.getMonthValue() - 1] + " " + dateTime.getYear();
    }

    private void cancelNotification(String fhirId) {
        notificationRepository.findByFhirAppointmentId(fhirId).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.CANCELLED);
            notificationRepository.save(notification);
            log.info("Appointment {} cancelled, notifications suppressed", fhirId);
        });
    }

    // ── FHIR field extractors ───────────────────────────────────────────────

    private Instant extractStart(Appointment appointment) {
        return appointment.getStart() != null
                ? appointment.getStart().toInstant()
                : null;
    }

    private String extractLocation(Appointment appointment) {
        return appointment.getParticipant().stream()
                .filter(p -> p.getActor() != null && p.getActor().getReference() != null)
                .filter(p -> p.getActor().getReference().startsWith("Location/"))
                .map(p -> p.getActor().getDisplay())
                .filter(d -> d != null && !d.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String extractPhone(Appointment appointment) {
        // In a real integration, patient contact would come from the Patient resource
        // For the FHIR Appointment, we use the extension or fall back to a placeholder
        return appointment.getExtension().stream()
                .filter(ext -> ext.getUrl() != null && ext.getUrl().contains("patient-phone"))
                .map(ext -> ext.getValue().toString())
                .findFirst()
                .orElse("+00000000000");   // Placeholder: real impl fetches Patient resource
    }

    private String extractPatientName(Appointment appointment) {
        return appointment.getParticipant().stream()
                .filter(p -> p.getActor() != null && p.getActor().getReference() != null)
                .filter(p -> p.getActor().getReference().startsWith("Patient/"))
                .map(p -> p.getActor().getDisplay())
                .findFirst()
                .orElse(null);
    }

    private String extractInstructions(Appointment appointment) {
        return appointment.getComment();
    }

    private ZoneId safeZone(String tz) {
        try { return ZoneId.of(tz); } catch (Exception e) { return ZoneId.of("UTC"); }
    }
}
