package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.MessageLog;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.messaging.NotificationProducer;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import nl.avans.communicatiemodule.security.EncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Notification scheduling and retry orchestration.
 *
 * Responsibilities:
 * - Check which appointments need notifications (24h and 1h reminders)
 * - Decrypt patient data and queue notifications to RabbitMQ
 * - Handle retries with exponential backoff
 * - Support provider fallback if one provider is down
 * - ACK receipt from providers (HL7 FHIR compliance)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSchedulingService {

    private final AppointmentNotificationRepository notificationRepository;
    private final OrganisationConfigRepository organisationRepository;
    private final MessageLogRepository messageLogRepository;
    private final NotificationProducer notificationProducer;
    private final EncryptionService encryptionService;

    @Value("${app.scheduling.poll-interval-ms:30000}")
    private long pollIntervalMs;

    @Value("${rabbitmq.max-retries:5}")
    private int maxRetries;

    /**
     * Base URL used when building per-appointment cancellation links.
     * e.g. "https://comm.hospital.nl"  →  link becomes
     *      "https://comm.hospital.nl/cancel/{orgId}/{token}"
     */
    @Value("${app.fhir.callback-base-url:http://localhost:8080}")
    private String appBaseUrl;

    /**
     * Scheduled task: Poll for appointments needing 24-hour notification.
     * Runs every 30 seconds (configurable).
     */
    @Scheduled(fixedRateString = "${app.scheduling.poll-interval-ms:30000}")
    @Transactional
    public void scheduleNotifications24h() {
        log.debug("Polling for 24-hour appointment notifications...");

        Instant now = Instant.now();
        var notifications = notificationRepository.findPendingNotificationsBefore24h(now);

        notifications.forEach(notification -> {
            if (!notification.isSent24h() && notification.getNotifyAt24h().isBefore(now)) {
                queueNotification(notification, "REMINDER_24H");
                notification.setSent24h(true);
                notificationRepository.save(notification);
                log.info("Queued 24h reminder for appointment {}", notification.getFhirAppointmentId());
            }
        });
    }

    /**
     * Scheduled task: Poll for appointments needing 1-hour notification.
     * Runs every 30 seconds (configurable).
     */
    @Scheduled(fixedRateString = "${app.scheduling.poll-interval-ms:30000}")
    @Transactional
    public void scheduleNotifications1h() {
        log.debug("Polling for 1-hour appointment notifications...");

        Instant now = Instant.now();
        var notifications = notificationRepository.findPendingNotificationsBefore1h(now);

        notifications.forEach(notification -> {
            if (!notification.isSent1h() && notification.getNotifyAt1h().isBefore(now)) {
                queueNotification(notification, "REMINDER_1H");
                notification.setSent1h(true);
                notificationRepository.save(notification);
                log.info("Queued 1h reminder for appointment {}", notification.getFhirAppointmentId());
            }
        });
    }

    /**
     * Decrypt patient data, build a NotificationMessage and put it on the queue.
     * The message body is built later (in NotificationConsumer) from the decrypted fields.
     */
    private void queueNotification(AppointmentNotification notification, String reminderType) {
        OrganisationConfig org = organisationRepository.findById(notification.getOrganisationId())
                .orElse(null);
        if (org == null) {
            log.error("Organisation not found for notification {}", notification.getId());
            return;
        }

        try {
            // ── Decrypt patient data before putting on queue ─────────────────
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

            // ── Build cancellation URL (requirement: link in message) ────────
            String cancellationUrl = appBaseUrl + "/cancel/"
                    + notification.getOrganisationId() + "/"
                    + notification.getCancellationToken();

            // ── Appointment start in organisation timezone ───────────────────
            Instant appointmentStart = notification.getAppointmentStartUtc()
                    .atZone(ZoneId.of(org.getTimezone()))
                    .toInstant();

            NotificationMessage message = new NotificationMessage(
                    notification.getId(),
                    notification.getOrganisationId(),
                    org.getProviderType(),
                    reminderType,
                    recipientPhone,
                    recipientName,
                    cancellationUrl,
                    appointmentStart,
                    appointmentDetails,
                    org.getLanguage(),
                    org.getTimezone(),
                    notification.getRetryCount()
            );

            notificationProducer.publish(message);

            // Log the dispatch attempt (no PII — only org/provider/type)
            MessageLog msgLog = new MessageLog();
            msgLog.setOrganisationId(org.getId());
            msgLog.setProviderType(org.getProviderType());
            msgLog.setNotificationType(reminderType);
            msgLog.setStatus(MessageLog.MessageStatus.PENDING);
            msgLog.setRetryCount(notification.getRetryCount());
            messageLogRepository.save(msgLog);

            log.info("Notification queued: appointmentId={}, reminder={}, retry={}",
                    notification.getFhirAppointmentId(), reminderType, notification.getRetryCount());

        } catch (Exception e) {
            log.error("Failed to queue notification: {}", e.getMessage(), e);
            notification.setLastError(e.getMessage());
            notification.setRetryCount(notification.getRetryCount() + 1);

            if (notification.getRetryCount() <= maxRetries) {
                notification.setStatus(NotificationStatus.RETRYING);
            } else {
                notification.setStatus(NotificationStatus.FAILED);
                log.warn("Notification exceeded max retries: {}", notification.getId());
            }
            notificationRepository.save(notification);
        }
    }

    /**
     * Retry failed notifications with exponential backoff.
     * Retries up to maxRetries times.
     * Runs every minute.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void retryFailedNotifications() {
        log.debug("Checking for notifications to retry...");

        var failedNotifications = notificationRepository.findByStatusAndRetryCountLessThan(
                NotificationStatus.RETRYING, maxRetries);

        failedNotifications.forEach(notification -> {
            long backoffSeconds = calculateBackoff(notification.getRetryCount());
            Instant nextRetryTime = notification.getCreatedAt().plusSeconds(backoffSeconds);

            if (nextRetryTime.isBefore(Instant.now())) {
                log.info("Retrying notification {} (attempt {})",
                        notification.getId(), notification.getRetryCount() + 1);
                queueNotification(notification,
                        notification.isSent24h() ? "REMINDER_1H" : "REMINDER_24H");
            }
        });
    }

    /**
     * Exponential backoff with jitter: 2^n seconds, capped at 1 hour.
     */
    private long calculateBackoff(int retryCount) {
        long baseBackoff = (long) Math.pow(2, Math.min(retryCount, 6));
        long withJitter = baseBackoff + (System.currentTimeMillis() % 10);
        return Math.min(withJitter, 3600);
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