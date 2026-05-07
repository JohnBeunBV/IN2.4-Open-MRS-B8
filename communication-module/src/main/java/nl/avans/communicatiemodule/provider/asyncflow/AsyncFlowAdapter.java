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

import java.time.Duration;

/**
 * AsyncFlow: Asynchronous provider.
 * Step 1: POST /asyncflow/submit       → returns { jobId }
 * Step 2: GET  /asyncflow/status/{id}  → poll until "DELIVERED" or "FAILED"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncFlowAdapter implements MessagingProvider {

    private final WebClient webClient;
    private final ProviderProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ASYNC_FLOW;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        ProviderProperties.AsyncFlowProps cfg = properties.getAsyncflow();
        String baseUrl = properties.getBaseUrl() + cfg.getPath();
        String apiKey  = cfg.getApiKey();

        log.debug("AsyncFlow: submitting message to={}", message.getRecipientPhone());

        try {
            // Step 1: Submit
            SubmitRequest request = new SubmitRequest(
                    message.getRecipientPhone(),
                    MessageTextBuilder.build(message));

            SubmitResponse submitResp = webClient.post()
                    .uri(baseUrl + "/submit")
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, res ->
                            res.bodyToMono(String.class).map(b ->
                                    new RuntimeException("AsyncFlow submit error " + res.statusCode() + ": " + b)))
                    .bodyToMono(SubmitResponse.class)
                    .block();

            if (submitResp == null || submitResp.getJobId() == null) {
                return SendResult.failure("AsyncFlow returned no jobId");
            }

            String jobId = submitResp.getJobId();
            log.info("AsyncFlow: submitted, jobId={}", jobId);

            // Step 2: Poll
            return pollForResult(baseUrl, apiKey, jobId, cfg);

        } catch (Exception ex) {
            log.error("AsyncFlow send failed: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private SendResult pollForResult(String baseUrl, String apiKey, String jobId,
                                     ProviderProperties.AsyncFlowProps cfg) throws InterruptedException {
        for (int attempt = 0; attempt < cfg.getMaxPollAttempts(); attempt++) {
            Thread.sleep(cfg.getPollIntervalMs());

            StatusResponse status = webClient.get()
                    .uri(baseUrl + "/status/" + jobId)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(StatusResponse.class)
                    .block();

            if (status == null) continue;

            log.debug("AsyncFlow: poll {}/{} jobId={} status={}",
                      attempt + 1, cfg.getMaxPollAttempts(), jobId, status.getStatus());

            switch (status.getStatus() == null ? "" : status.getStatus().toUpperCase()) {
                case "DELIVERED", "SENT", "SUCCESS" ->  {
                    log.info("AsyncFlow: jobId={} delivered", jobId);
                    return SendResult.success(jobId, status.toString());
                }
                case "FAILED", "ERROR" -> {
                    return SendResult.failure("AsyncFlow reported FAILED for jobId=" + jobId, status.toString());
                }
                // PENDING / QUEUED → keep polling
            }
        }
        return SendResult.failure("AsyncFlow: polling timed out for jobId=" + jobId);
    }

    // ── Inner DTOs ──────────────────────────────────────────────────────────

    @Data static class SubmitRequest {
        private final String to;
        private final String message;
    }

    @Data static class SubmitResponse {
        @JsonProperty("job_id") private String jobId;
    }

    @Data static class StatusResponse {
        @JsonProperty("job_id") private String jobId;
        private String status;
        private String detail;
    }
}
