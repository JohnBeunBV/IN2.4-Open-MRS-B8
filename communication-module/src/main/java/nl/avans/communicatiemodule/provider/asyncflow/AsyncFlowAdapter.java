package nl.avans.communicatiemodule.provider.asyncflow;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncFlowAdapter implements MessagingProvider {

    private static final String STUDENT_GROUP = "groep-8";

    private final WebClient webClient;
    private final ProviderProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ASYNC_FLOW;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        ProviderProperties.AsyncFlowProps cfg = properties.getAsyncflow();
        String url    = properties.getBaseUrl() + cfg.getPath();
        String apiKey = cfg.getApiKey();

        log.debug("AsyncFlow: POST {} to={}", url, message.getRecipientPhone());

        try {
            // Stap 1: submit
            SubmitRequest request = new SubmitRequest(
                    message.getRecipientPhone(),
                    MessageTextBuilder.build(message),
                    "normal"
            );

            SubmitResponse submitResp = webClient.post()
                    .uri(url)
                    .header("X-API-KEY", apiKey)
                    .header("X-STUDENT-GROUP", STUDENT_GROUP)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, res ->
                            res.bodyToMono(String.class).map(b ->
                                    new RuntimeException("AsyncFlow submit error " + res.statusCode() + ": " + b)))
                    .bodyToMono(SubmitResponse.class)
                    .block();

            if (submitResp == null || submitResp.getTrackingId() == null) {
                return SendResult.failure("AsyncFlow: geen trackingId ontvangen");
            }

            String trackingId = submitResp.getTrackingId();
            log.info("AsyncFlow: ingediend, trackingId={}", trackingId);

            // Stap 2: poll status
            return pollStatus(url, apiKey, trackingId, cfg);

        } catch (Exception ex) {
            log.error("AsyncFlow send mislukt: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private SendResult pollStatus(String url, String apiKey, String trackingId,
                                  ProviderProperties.AsyncFlowProps cfg) throws InterruptedException {
        for (int i = 0; i < cfg.getMaxPollAttempts(); i++) {
            Thread.sleep(cfg.getPollIntervalMs());

            StatusResponse status = webClient.get()
                    .uri(url + "/" + trackingId)
                    .header("X-API-KEY", apiKey)
                    .header("X-STUDENT-GROUP", STUDENT_GROUP)
                    .retrieve()
                    .bodyToMono(StatusResponse.class)
                    .block();

            if (status == null) continue;

            log.debug("AsyncFlow poll {}/{} trackingId={} status={}",
                      i + 1, cfg.getMaxPollAttempts(), trackingId, status.getStatus());

            switch (status.getStatus() == null ? "" : status.getStatus()) {
                case "Completed" -> {
                    log.info("AsyncFlow: trackingId={} afgeleverd", trackingId);
                    return SendResult.success(trackingId, status.toString());
                }
                case "Failed" -> {
                    return SendResult.failure(
                            "AsyncFlow: Failed voor trackingId=" + trackingId
                            + " - " + status.getErrorDetails(),
                            status.toString());
                }
                // Queued / Processing → blijven pollen
            }
        }
        return SendResult.failure("AsyncFlow: polling timeout voor trackingId=" + trackingId);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    @Data static class SubmitRequest {
        private final String destination;
        private final String content;
        private final String priority;
    }

    @Data static class SubmitResponse {
        private boolean accepted;
        @JsonProperty("trackingId") private String trackingId;
        private String message;
        private String submittedAt;
    }

    @Data static class StatusResponse {
        @JsonProperty("trackingId")    private String trackingId;
        private String status;
        private String submittedAt;
        private String processedAt;
        @JsonProperty("errorDetails") private String errorDetails;
    }
}