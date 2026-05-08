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

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SwiftSendAdapter implements MessagingProvider {

    private static final String STUDENT_GROUP = "groep-8";

    private final WebClient webClient;
    private final ProviderProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.SWIFT_SEND;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        String url    = properties.getBaseUrl() + properties.getSwiftsend().getPath();
        String apiKey = properties.getSwiftsend().getApiKey();
        String body   = MessageTextBuilder.build(message);

        SendRequest request = new SendRequest(
                "SMS",
                List.of(message.getRecipientPhone()),
                body
        );

        log.debug("SwiftSend: POST {} to={}", url, message.getRecipientPhone());

        try {
            SendResponse response = webClient.post()
                    .uri(url)
                    .header("X-API-KEY", apiKey)
                    .header("X-STUDENT-GROUP", STUDENT_GROUP)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, res ->
                            res.bodyToMono(String.class).map(b ->
                                    new RuntimeException("SwiftSend error " + res.statusCode() + ": " + b)))
                    .bodyToMono(SendResponse.class)
                    .block();

            if (response != null && response.getMessageId() != null) {
                log.info("SwiftSend: sent messageId={}", response.getMessageId());
                return SendResult.success(response.getMessageId(), response.toString());
            }
            return SendResult.failure("SwiftSend: lege response");
        } catch (Exception ex) {
            log.error("SwiftSend send mislukt: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    @Data
    static class SendRequest {
        private final String type;
        private final List<String> recipients;
        private final String content;
    }

    @Data
    static class SendResponse {
        private boolean success;
        @JsonProperty("messageId")
        private String messageId;
        private List<String> failedRecipients;
        private String error;
    }
}