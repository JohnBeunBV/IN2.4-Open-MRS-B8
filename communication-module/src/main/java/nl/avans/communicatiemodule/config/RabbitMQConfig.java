package nl.avans.communicatiemodule.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue}")
    private String queue;

    @Value("${rabbitmq.dead-letter-queue}")
    private String deadLetterQueue;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    @Value("${rabbitmq.max-retries:5}")
    private int maxRetries;

    // ── Dead-letter infrastructure ──────────────────────────────────────────

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange("notification.dlx");
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(routingKey + ".dead");
    }

    // ── Main exchange and queue ─────────────────────────────────────────────

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(exchange);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "notification.dlx")
                .withArgument("x-dead-letter-routing-key", routingKey + ".dead")
                .withArgument("x-message-ttl", 3_600_000)   // 1 hour TTL
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(notificationExchange())
                .with(routingKey);
    }

    // ── Serialization ───────────────────────────────────────────────────────

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        factory.setDefaultRequeueRejected(false);   // send to DLQ on exception
        return factory;
    }
}
