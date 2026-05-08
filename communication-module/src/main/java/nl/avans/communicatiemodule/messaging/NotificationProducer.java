package nl.avans.communicatiemodule.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    public void publish(NotificationMessage message) {
        log.debug("Publishing notification to queue: notificationId={}, type={}",
                  message.getNotificationId(), message.getNotificationType());
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}
