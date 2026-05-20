package nl.avans.communicatiemodule.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import nl.avans.communicatiemodule.service.NotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final AppointmentNotificationRepository notificationRepository;
    private final MessageLogRepository messageLogRepository;

    // ── Metrics ─────────────────────────────────────────────────────────────
    private final Counter dispatched24hCounter;
    private final Counter dispatched1hCounter;
    private final Counter schedulerErrorCounter;
    private final Counter purgeCounter;
    private final AtomicInteger lastTickDispatched = new AtomicInteger(0);
    private final AtomicLong lastSuccessfulTickEpoch = new AtomicLong(Instant.now().getEpochSecond());

    /** How many consecutive scheduler ticks may produce zero dispatches before logging a warning. */
    private static final int IDLE_TICK_ALERT_THRESHOLD = 60; // ~30 min at 30s interval
    private int idleTickCount = 0;

    public NotificationScheduler(NotificationService notificationService,
                                 AppointmentNotificationRepository notificationRepository,
                                 MessageLogRepository messageLogRepository,
                                 MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.messageLogRepository = messageLogRepository;

        this.dispatched24hCounter = Counter.builder("scheduler.notifications.dispatched")
                .tag("type", "24h")
                .description("Total 24h notifications dispatched by scheduler")
                .register(meterRegistry);
        this.dispatched1hCounter = Counter.builder("scheduler.notifications.dispatched")
                .tag("type", "1h")
                .description("Total 1h notifications dispatched by scheduler")
                .register(meterRegistry);
        this.schedulerErrorCounter = Counter.builder("scheduler.errors")
                .description("Number of errors during scheduler execution")
                .register(meterRegistry);
        this.purgeCounter = Counter.builder("scheduler.purge.records")
                .description("Total patient data records purged for GDPR")
                .register(meterRegistry);

        // Gauge: seconds since last successful scheduler tick (alerts when stale)
        Gauge.builder("scheduler.last_success_age_seconds", lastSuccessfulTickEpoch,
                      ts -> Instant.now().getEpochSecond() - ts.get())
                .description("Seconds since the last successful scheduler tick")
                .register(meterRegistry);

        // Gauge: dispatched on last tick (useful for alerting on sustained zero)
        Gauge.builder("scheduler.last_tick_dispatched", lastTickDispatched, AtomicInteger::get)
                .description("Number of notifications dispatched in the last scheduler tick")
                .register(meterRegistry);
    }

    /**
     * Main scheduling loop: checks for due 24h and 1h notifications every 30s.
     */
    @Scheduled(fixedDelayString = "${app.scheduling.poll-interval-ms:30000}")
    public void dispatchDueNotifications() {
        log.debug("Scheduler tick: checking for due notifications");
        try {
            int count24h = notificationService.dispatchDue24hNotifications();
            int count1h  = notificationService.dispatchDue1hNotifications();
            int total = count24h + count1h;

            dispatched24hCounter.increment(count24h);
            dispatched1hCounter.increment(count1h);
            lastTickDispatched.set(total);
            lastSuccessfulTickEpoch.set(Instant.now().getEpochSecond());

            if (total == 0) {
                idleTickCount++;
                if (idleTickCount >= IDLE_TICK_ALERT_THRESHOLD) {
                    log.warn("Scheduler has produced 0 dispatches for {} consecutive ticks (~{}min) - " +
                             "check pending notifications or appointment data",
                             idleTickCount, (idleTickCount * 30) / 60);
                    idleTickCount = 0; // reset to avoid log spam
                }
            } else {
                idleTickCount = 0;
                log.debug("Scheduler tick complete: dispatched 24h={}, 1h={}", count24h, count1h);
            }
        } catch (Exception ex) {
            schedulerErrorCounter.increment();
            log.error("Error dispatching notifications: {}", ex.getMessage(), ex);
        }
    }

    /** GDPR cleanup: nullify patient PII where the 14-day expiry has passed. Runs daily at 02:00. */
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredPatientData() {
        log.info("Running GDPR patient data purge");
        try {
            int purged = notificationRepository.purgeExpiredPatientData(Instant.now());
            purgeCounter.increment(purged);
            log.info("Purged patient data from {} records", purged);
        } catch (Exception ex) {
            schedulerErrorCounter.increment();
            log.error("GDPR purge job failed: {}", ex.getMessage(), ex);
        }
    }

    /** Message log retention: delete logs older than 1 year. Runs daily at 03:00. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredMessageLogs() {
        log.info("Running message log retention cleanup");
        try {
            int deleted = messageLogRepository.deleteExpiredLogs(Instant.now());
            log.info("Deleted {} expired message log entries", deleted);
        } catch (Exception ex) {
            schedulerErrorCounter.increment();
            log.error("Message log purge job failed: {}", ex.getMessage(), ex);
        }
    }

    /** Appointment record cleanup: delete fully expired records. Runs daily at 04:00. */
    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpiredAppointmentRecords() {
        log.info("Running appointment record cleanup");
        try {
            Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
            int deleted = notificationRepository.deleteExpiredRecords(cutoff);
            log.info("Deleted {} expired appointment notification records", deleted);
        } catch (Exception ex) {
            schedulerErrorCounter.increment();
            log.error("Appointment record purge job failed: {}", ex.getMessage(), ex);
        }
    }
}
