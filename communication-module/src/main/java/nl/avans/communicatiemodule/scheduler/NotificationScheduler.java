package nl.avans.communicatiemodule.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import nl.avans.communicatiemodule.service.NotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final AppointmentNotificationRepository notificationRepository;
    private final MessageLogRepository messageLogRepository;

    /**
     * Main scheduling loop: checks for due 24h and 1h notifications.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelayString = "${app.scheduling.poll-interval-ms:30000}")
    public void dispatchDueNotifications() {
        log.debug("Scheduler tick: checking for due notifications");
        try {
            notificationService.dispatchDue24hNotifications();
            notificationService.dispatchDue1hNotifications();
        } catch (Exception ex) {
            log.error("Error dispatching notifications: {}", ex.getMessage(), ex);
        }
    }

    /**
     * GDPR cleanup: nullify patient PII where the 14-day expiry has passed.
     * Runs daily at 02:00.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredPatientData() {
        log.info("Running GDPR patient data purge");
        int purged = notificationRepository.purgeExpiredPatientData(Instant.now());
        log.info("Purged patient data from {} records", purged);
    }

    /**
     * Message log retention cleanup: delete logs older than 1 year.
     * Runs daily at 03:00.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredMessageLogs() {
        log.info("Running message log retention cleanup");
        int deleted = messageLogRepository.deleteExpiredLogs(Instant.now());
        log.info("Deleted {} expired message log entries", deleted);
    }

    /**
     * Appointment record cleanup: delete fully expired appointment records.
     * Runs daily at 04:00.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpiredAppointmentRecords() {
        log.info("Running appointment record cleanup");
        // Keep a 30-day buffer beyond the 14-day GDPR window before hard-deleting
        Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
        int deleted = notificationRepository.deleteExpiredRecords(cutoff);
        log.info("Deleted {} expired appointment notification records", deleted);
    }
}
