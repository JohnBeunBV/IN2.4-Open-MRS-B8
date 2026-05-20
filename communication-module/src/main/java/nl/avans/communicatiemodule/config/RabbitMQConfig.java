package nl.avans.communicatiemodule.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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

    // ── Connection with reconnect / backoff ─────────────────────────────────

    @Bean
    public CachingConnectionFactory cachingConnectionFactory(
            @Value("${spring.rabbitmq.host}") String host,
            @Value("${spring.rabbitmq.port}") int port,
            @Value("${spring.rabbitmq.username}") String username,
            @Value("${spring.rabbitmq.password}") String password) {

        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        // Publisher confirms allow the producer to detect lost messages
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        // Reconnect: Spring AMQP retries internally; cache size = 1 channel per thread
        factory.setChannelCacheSize(10);
        return factory;
    }

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
        // Return unroutable messages instead of silently dropping
        template.setMandatory(true);
        // Retry failed sends up to 3 times with 1s initial backoff
        org.springframework.retry.support.RetryTemplate retry = new org.springframework.retry.support.RetryTemplate();
        org.springframework.retry.backoff.ExponentialBackOffPolicy backOff =
                new org.springframework.retry.backoff.ExponentialBackOffPolicy();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        retry.setBackOffPolicy(backOff);
        org.springframework.retry.policy.SimpleRetryPolicy retryPolicy =
                new org.springframework.retry.policy.SimpleRetryPolicy(3);
        retry.setRetryPolicy(retryPolicy);
        template.setRetryTemplate(retry);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        factory.setDefaultRequeueRejected(false);   // send to DLQ on unhandled exception
        // Automatic recovery: reconnect and resume consuming after broker restart
        factory.setMissingQueuesFatal(false);
        factory.setFailedDeclarationRetryInterval(Duration.ofSeconds(5).toMillis());
        return factory;
    }
}
