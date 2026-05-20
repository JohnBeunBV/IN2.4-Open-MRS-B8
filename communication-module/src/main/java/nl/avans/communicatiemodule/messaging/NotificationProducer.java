package nl.avans.communicatiemodule.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes notification messages to RabbitMQ.
 * Falls back gracefully when the broker is unavailable: the notification
 * is marked FAILED in the database so the scheduler can retry on the next tick.
 */
@Slf4j
@Component
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;
    private final AppointmentNotificationRepository notificationRepository;
    private final Counter queueFailureCounter;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    public NotificationProducer(RabbitTemplate rabbitTemplate,
                                AppointmentNotificationRepository notificationRepository,
                                MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.notificationRepository = notificationRepository;
        this.queueFailureCounter = Counter.builder("rabbitmq.publish.failures")
                .description("Number of times a message could not be published to RabbitMQ")
                .register(meterRegistry);
    }

    /**
     * Publish a notification message. If RabbitMQ is unavailable the notification
     * status is set to FAILED so it will be picked up again on the next scheduler tick.
     */
    public void publish(NotificationMessage message) {
        log.debug("Publishing notification to queue: notificationId={}, type={}",
                  message.getNotificationId(), message.getNotificationType());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (AmqpException ex) {
            queueFailureCounter.increment();
            log.error("RabbitMQ unavailable - could not queue notificationId={}: {}. " +
                      "Marking FAILED so scheduler retries on next tick.",
                      message.getNotificationId(), ex.getMessage());
            // Fallback: mark as FAILED; the scheduler will re-dispatch on the next poll
            notificationRepository.findById(message.getNotificationId()).ifPresent(n -> {
                n.setStatus(NotificationStatus.FAILED);
                notificationRepository.save(n);
            });
        }
    }
}
