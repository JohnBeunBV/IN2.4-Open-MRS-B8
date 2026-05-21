package nl.avans.communicatiemodule.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;
    private final AppointmentNotificationRepository notificationRepository;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    /**
     * Publish a notification. If RabbitMQ is unavailable the notification is marked
     * FAILED so the scheduler retries it on the next tick.
     */
    public void publish(NotificationMessage message) {
        log.debug("Publishing notification: notificationId={}, type={}",
                  message.getNotificationId(), message.getNotificationType());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (AmqpException ex) {
            log.error("RabbitMQ unavailable — notificationId={}: {}. Marking FAILED for retry.",
                      message.getNotificationId(), ex.getMessage());
            notificationRepository.findById(message.getNotificationId()).ifPresent(n -> {
                n.setStatus(NotificationStatus.FAILED);
                notificationRepository.save(n);
            });
        }
    }
}
