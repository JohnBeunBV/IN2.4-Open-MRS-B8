package nl.avans.communicatiemodule.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.MessageLog;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.provider.MessagingProvider;
import nl.avans.communicatiemodule.provider.MessagingProviderFactory;
import nl.avans.communicatiemodule.provider.SendResult;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final MessagingProviderFactory providerFactory;
    private final AppointmentNotificationRepository notificationRepository;
    private final MessageLogRepository messageLogRepository;
    private final NotificationProducer producer;

    private static final int MAX_RETRIES = 5;

    @RabbitListener(queues = "${rabbitmq.queue}", containerFactory = "rabbitListenerContainerFactory")
    @Transactional
    public void consume(NotificationMessage message) {
        log.info("Consuming notification: id={}, type={}, provider={}",
                 message.getNotificationId(), message.getNotificationType(), message.getProviderType());

        MessagingProvider provider = providerFactory.getProvider(message.getProviderType());
        SendResult result;

        try {
            result = provider.send(message);
        } catch (Exception ex) {
            log.error("Provider threw exception for notificationId={}", message.getNotificationId(), ex);
            result = SendResult.failure("Exception: " + ex.getMessage());
        }

        MessageLog logEntry = buildLogEntry(message, result);
        messageLogRepository.save(logEntry);

        if (result.isSuccess()) {
            markSent(message);
        } else {
            handleFailure(message, result);
        }
    }

    private void markSent(NotificationMessage message) {
        notificationRepository.findById(message.getNotificationId()).ifPresent(notification -> {
            if ("REMINDER_24H".equals(message.getNotificationType())) {
                notification.setSent24h(true);
                if (notification.isSent1h()) {
                    notification.setStatus(NotificationStatus.COMPLETED);
                } else {
                    notification.setStatus(NotificationStatus.PARTIAL);
                }
            } else {
                notification.setSent1h(true);
                if (notification.isSent24h()) {
                    notification.setStatus(NotificationStatus.COMPLETED);
                }
            }
            notificationRepository.save(notification);
            log.info("Notification marked sent: id={}, type={}", message.getNotificationId(), message.getNotificationType());
        });
    }

    private void handleFailure(NotificationMessage message, SendResult result) {
        int nextRetry = message.getRetryCount() + 1;
        log.warn("Send failed (attempt {}/{}): notificationId={}, reason={}",
                 nextRetry, MAX_RETRIES, message.getNotificationId(), result.getErrorMessage());

        if (nextRetry < MAX_RETRIES) {
            // Re-queue with incremented retry counter (exponential backoff via message TTL)
            message.setRetryCount(nextRetry);
            producer.publish(message);
        } else {
            log.error("Max retries reached for notificationId={}, marking FAILED", message.getNotificationId());
            notificationRepository.findById(message.getNotificationId()).ifPresent(n -> {
                n.setStatus(NotificationStatus.FAILED);
                notificationRepository.save(n);
            });
        }
    }

    private MessageLog buildLogEntry(NotificationMessage message, SendResult result) {
        MessageLog entry = new MessageLog();
        entry.setOrganisationId(message.getOrganisationId());
        entry.setProviderType(message.getProviderType());
        entry.setNotificationType(message.getNotificationType());
        entry.setProviderMessageRef(result.getProviderReference());
        entry.setRetryCount(message.getRetryCount());
        entry.setProviderResponse(result.getRawResponse());
        if (result.isSuccess()) {
            entry.setStatus(MessageLog.MessageStatus.SENT);
            entry.setSentAt(Instant.now());
        } else {
            entry.setStatus(message.getRetryCount() >= MAX_RETRIES - 1
                    ? MessageLog.MessageStatus.FAILED
                    : MessageLog.MessageStatus.RETRYING);
            entry.setErrorMessage(result.getErrorMessage());
        }
        return entry;
    }
}
