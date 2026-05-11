package nl.avans.communicatiemodule.provider.securepost;

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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurePostAdapter implements MessagingProvider {

    private static final String STUDENT_GROUP = "groep-8";

    private final WebClient webClient;
    private final ProviderProperties properties;

    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.SECURE_POST;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        String baseUrl = properties.getBaseUrl() + properties.getSecurepost().getPath();
        String body    = MessageTextBuilder.build(message);

        try {
            String token = getOrRefreshToken(baseUrl);

            // ✅ Fix: opslaan in variabele
            SendRequest request = new SendRequest(
                    "SMS",
                    message.getRecipientPhone(),
                    body,
                    "Afspraakherinnering"
            );

            SendResponse response = webClient.post()
                    .uri(baseUrl + "/message")
                    .header("Authorization", "Bearer " + token)
                    .header("X-STUDENT-GROUP", STUDENT_GROUP)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, res -> {
                        tokenCache.set(null);
                        return res.createException();
                    })
                    .onStatus(HttpStatusCode::isError, res ->
                            res.bodyToMono(String.class).map(b ->
                                    new RuntimeException("SecurePost error " + res.statusCode() + ": " + b)))
                    .bodyToMono(SendResponse.class)
                    .block();

            if (response != null && response.getTrackingId() != null) {
                log.info("SecurePost: sent trackingId={}", response.getTrackingId());
                return SendResult.success(response.getTrackingId(), response.toString());
            }
            return SendResult.failure("SecurePost: lege response");

        } catch (Exception ex) {
            log.error("SecurePost send mislukt voor {}: {}", message.getRecipientPhone(), ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private String getOrRefreshToken(String baseUrl) {
        CachedToken cached = tokenCache.get();
        if (cached != null && cached.isValid()) {
            return cached.token();
        }

        log.debug("SecurePost: nieuw JWT token ophalen");
        ProviderProperties.SecurePostProps cfg = properties.getSecurepost();

        TokenRequest req = new TokenRequest(cfg.getUsername(), cfg.getPassword());

        TokenResponse resp = webClient.post()
                .uri(baseUrl + "/auth")
                .header("Content-Type", "application/json")
                .header("X-STUDENT-GROUP", STUDENT_GROUP)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .block();

        if (resp == null || resp.getAccessToken() == null) {
            throw new RuntimeException("SecurePost: kon geen JWT token ophalen");
        }

        long ttl = resp.getExpiresIn() > 0 ? resp.getExpiresIn() - 30 : 150;
        tokenCache.set(new CachedToken(resp.getAccessToken(), Instant.now().plusSeconds(ttl)));
        return resp.getAccessToken();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    @Data static class TokenRequest {
        @JsonProperty("clientId")
        private final String clientId;
        @JsonProperty("clientSecret")
        private final String clientSecret;
    }

    @Data static class TokenResponse {
        @JsonProperty("accessToken")  private String accessToken;
        @JsonProperty("expiresIn")    private long expiresIn;
    }

    @Data static class SendRequest {
        private final String format;
        private final String recipient;
        private final String body;
        private final String subject;
    }

    @Data static class SendResponse {
        private boolean delivered;
        @JsonProperty("trackingId")        private String trackingId;
        @JsonProperty("errorMessage")      private String errorMessage;
        @JsonProperty("deliveryTimestamp") private String deliveryTimestamp;
    }

    record CachedToken(String token, Instant expiresAt) {
        boolean isValid() { return Instant.now().isBefore(expiresAt); }
    }
}