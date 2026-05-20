package nl.avans.communicatiemodule.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Enforces a global webhook token on all /fhir/webhook/** requests.
 * Uses constant-time comparison to prevent timing attacks.
 * Per-org token validation is still done inside FhirWebhookController.
 */
@Slf4j
public class WebhookTokenFilter extends OncePerRequestFilter {

    private final byte[] expectedTokenBytes;
    private final Counter authFailureCounter;

    public WebhookTokenFilter(String globalWebhookToken, MeterRegistry meterRegistry) {
        this.expectedTokenBytes = globalWebhookToken.getBytes(StandardCharsets.UTF_8);
        this.authFailureCounter = Counter.builder("webhook.auth.failures")
                .description("Number of failed webhook authentication attempts")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        log.debug("Inbound FHIR webhook: method={} uri={} remoteAddr={}",
                  request.getMethod(), request.getRequestURI(), request.getRemoteAddr());

        String authHeader = request.getHeader("Authorization");
        if (!isValidGlobalToken(authHeader)) {
            authFailureCounter.increment();
            log.warn("Webhook rejected - invalid or missing global token. remoteAddr={}",
                     request.getRemoteAddr());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("Unauthorized");
            return;  // do NOT continue the filter chain
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/fhir/webhook");
    }

    /**
     * Constant-time comparison to prevent timing-based token enumeration.
     */
    private boolean isValidGlobalToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String received = authHeader.substring(7).trim();
        byte[] receivedBytes = received.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(receivedBytes, expectedTokenBytes);
    }
}
