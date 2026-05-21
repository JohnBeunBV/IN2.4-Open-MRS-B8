package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.messaging.NotificationProducer;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import nl.avans.communicatiemodule.security.EncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AppointmentNotificationRepository notificationRepository;
    private final OrganisationConfigRepository organisationRepository;
    private final NotificationProducer producer;
    private final EncryptionService encryptionService;

    /**
     * Base URL used when building per-appointment cancellation links.
     * Shared with NotificationSchedulingService — both use the same property.
     */
    @Value("${app.fhir.callback-base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Transactional
    public void dispatchDue24hNotifications() {
        List<AppointmentNotification> due = notificationRepository.findDue24hNotifications(Instant.now());
        log.debug("Found {} due 24h notifications", due.size());
        due.forEach(n -> dispatch(n, "REMINDER_24H"));
    }

    @Transactional
    public void dispatchDue1hNotifications() {
        List<AppointmentNotification> due = notificationRepository.findDue1hNotifications(Instant.now());
        log.debug("Found {} due 1h notifications", due.size());
        due.forEach(n -> dispatch(n, "REMINDER_1H"));
    }

    private void dispatch(AppointmentNotification notification, String type) {
        OrganisationConfig org = organisationRepository.findById(notification.getOrganisationId()).orElse(null);

        if (org == null || !org.isActive()) {
            log.warn("Organisation {} not found or inactive, skipping notification {}",
                    notification.getOrganisationId(), notification.getId());
            return;
        }

        Instant appointmentStart = notification.getAppointmentStartUtc() != null
                ? notification.getAppointmentStartUtc()
                        .atZone(ZoneId.of(org.getTimezone()))
                        .toInstant()
                : Instant.now();

        if (appointmentStart.isBefore(Instant.now())) {
            log.info("Appointment {} already started, skipping notification", notification.getFhirAppointmentId());
            if ("REMINDER_24H".equals(type)) {
                notification.setSent24h(true);
            } else {
                notification.setSent1h(true);
            }
            updateStatus(notification);
            notificationRepository.save(notification);
            return;
        }

        // ── Decrypt patient data before building the message ────────────────
        String recipientPhone = decryptSafe(
                notification.getPatientContactEncrypted(),
                org.getVaultCredentialsPath() + "/patient-contact",
                "unknown");

        String recipientName = decryptSafe(
                notification.getPatientNameEncrypted(),
                org.getVaultCredentialsPath() + "/patient-name",
                "Patient");

        String appointmentDetails = decryptSafe(
                notification.getAppointmentDetailsEncrypted(),
                org.getVaultCredentialsPath() + "/appointment-details",
                "");

        // ── Build cancellation URL (requirement: link in message) ────────────
        String cancellationUrl = appBaseUrl + "/cancel/"
                + notification.getOrganisationId() + "/"
                + notification.getCancellationToken();

        NotificationMessage message = new NotificationMessage(
                notification.getId(),
                org.getId(),
                org.getProviderType(),
                type,
                recipientPhone,
                recipientName,
                cancellationUrl,
                appointmentStart,
                appointmentDetails,
                org.getLanguage(),
                org.getTimezone(),
                0
        );

        producer.publish(message);
        log.info("Queued {} notification: notificationId={}, org={}", type, notification.getId(), org.getName());
    }

    private void updateStatus(AppointmentNotification n) {
        if (n.isSent24h() && n.isSent1h()) {
            n.setStatus(NotificationStatus.COMPLETED);
        } else if (n.isSent24h()) {
            n.setStatus(NotificationStatus.PARTIAL);
        }
    }

    /**
     * Decrypt a ciphertext; return fallback if null/blank or decryption fails.
     */
    private String decryptSafe(String ciphertext, String keyPath, String fallback) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return fallback;
        }
        try {
            return encryptionService.decrypt(ciphertext, keyPath);
        } catch (Exception e) {
            log.warn("Decryption failed for keyPath={}: {}", keyPath, e.getMessage());
            return fallback;
        }
    }
}