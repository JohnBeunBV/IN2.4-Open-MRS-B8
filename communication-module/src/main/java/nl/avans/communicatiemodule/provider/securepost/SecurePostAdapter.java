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

/**
 * SecurePost: REST API secured with JWT.
 * Step 1: POST /securepost/auth/token  → obtain Bearer token
 * Step 2: POST /securepost/messages with Authorization: Bearer {token}
 *
 * Token is cached in-memory and refreshed on 401.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurePostAdapter implements MessagingProvider {

    private final WebClient webClient;
    private final ProviderProperties properties;

    /** Simple in-memory token cache */
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
            return doSend(baseUrl, token, message.getRecipientPhone(), body);
        } catch (Exception ex) {
            log.error("SecurePost send failed: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private SendResult doSend(String baseUrl, String token, String to, String body) {
        SendRequest request = new SendRequest(to, body);

        SendResponse response = webClient.post()
                .uri(baseUrl + "/messages")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.value() == 401, res -> {
                    // Token expired – invalidate cache and retry
                    tokenCache.set(null);
                    return res.createException();
                })
                .onStatus(HttpStatusCode::isError, res ->
                        res.bodyToMono(String.class).map(b ->
                                new RuntimeException("SecurePost error " + res.statusCode() + ": " + b)))
                .bodyToMono(SendResponse.class)
                .block();

        if (response != null && response.getId() != null) {
            log.info("SecurePost: message sent, id={}", response.getId());
            return SendResult.success(response.getId(), response.toString());
        }
        return SendResult.failure("SecurePost returned empty response");
    }

    private String getOrRefreshToken(String baseUrl) {
        CachedToken cached = tokenCache.get();
        if (cached != null && cached.isValid()) {
            return cached.token;
        }

        log.debug("SecurePost: obtaining new JWT token");
        ProviderProperties.SecurePostProps cfg = properties.getSecurepost();

        TokenRequest req = new TokenRequest(cfg.getUsername(), cfg.getPassword());
        TokenResponse resp = webClient.post()
                .uri(baseUrl + "/auth/token")
                .header("Content-Type", "application/json")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .block();

        if (resp == null || resp.getAccessToken() == null) {
            throw new RuntimeException("SecurePost: failed to obtain JWT token");
        }

        // Cache for (expiresIn - 30s) to avoid edge-case expiry
        long ttl = resp.getExpiresIn() > 0 ? resp.getExpiresIn() - 30 : 3570;
        tokenCache.set(new CachedToken(resp.getAccessToken(), Instant.now().plusSeconds(ttl)));
        return resp.getAccessToken();
    }

    // ── Inner DTOs ──────────────────────────────────────────────────────────

    @Data static class TokenRequest {
        private final String username;
        private final String password;
    }

    @Data static class TokenResponse {
        @JsonProperty("access_token") private String accessToken;
        @JsonProperty("expires_in")   private long expiresIn;
    }

    @Data static class SendRequest {
        private final String to;
        private final String message;
    }

    @Data static class SendResponse {
        private String id;
        private String status;
    }

    record CachedToken(String token, Instant expiresAt) {
        boolean isValid() { return Instant.now().isBefore(expiresAt); }
    }
}
