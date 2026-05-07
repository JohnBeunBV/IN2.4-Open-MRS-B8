package nl.avans.communicatiemodule.provider.swiftsend;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.config.ProviderProperties;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.provider.MessageTextBuilder;
import nl.avans.communicatiemodule.provider.MessagingProvider;
import nl.avans.communicatiemodule.provider.SendResult;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * SwiftSend: Traditional REST API, authenticated via X-API-KEY header.
 * POST {baseUrl}/swiftsend/messages
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwiftSendAdapter implements MessagingProvider {

    private final WebClient webClient;
    private final ProviderProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.SWIFT_SEND;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        String baseUrl = properties.getBaseUrl() + properties.getSwiftsend().getPath();
        String apiKey  = properties.getSwiftsend().getApiKey();
        String body    = MessageTextBuilder.build(message);

        SendRequest request = new SendRequest(message.getRecipientPhone(), body);

        log.debug("SwiftSend: POST {}/messages to={}", baseUrl, message.getRecipientPhone());

        try {
            SendResponse response = webClient.post()
                    .uri(baseUrl + "/messages")
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, res ->
                            res.bodyToMono(String.class).map(b ->
                                    new RuntimeException("SwiftSend error " + res.statusCode() + ": " + b)))
                    .bodyToMono(SendResponse.class)
                    .block();

            if (response != null && response.getMessageId() != null) {
                log.info("SwiftSend: message sent, messageId={}", response.getMessageId());
                return SendResult.success(response.getMessageId(), response.toString());
            }
            return SendResult.failure("SwiftSend returned empty response");
        } catch (Exception ex) {
            log.error("SwiftSend send failed: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    // ── Inner DTOs ──────────────────────────────────────────────────────────

    @Data
    static class SendRequest {
        private final String to;
        private final String message;
    }

    @Data
    static class SendResponse {
        @JsonProperty("message_id")
        private String messageId;
        private String status;
    }
}
