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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AppointmentNotificationRepository notificationRepository;
    private final OrganisationConfigRepository organisationRepository;
    private final NotificationProducer producer;

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

        if (notification.getAppointmentStart().isBefore(Instant.now())) {
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

        NotificationMessage message = new NotificationMessage(
                notification.getId(),
                org.getId(),
                org.getProviderType(),
                type,
                notification.getPatientPhone(),
                notification.getPatientName(),
                notification.getAppointmentLocation(),
                notification.getAppointmentStart(),
                notification.getAppointmentInstructions(),
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
}