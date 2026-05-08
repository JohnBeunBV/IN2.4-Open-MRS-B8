package nl.avans.communicatiemodule.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that logs inbound FHIR webhook calls.
 * Actual per-org token validation is done inside FhirWebhookController.
 * This filter ensures no raw body data (which may contain FHIR PII) is ever logged.
 */
@Slf4j
@RequiredArgsConstructor
public class WebhookTokenFilter extends OncePerRequestFilter {

    private final String globalWebhookToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (request.getRequestURI().startsWith("/fhir/webhook")) {
            log.debug("Inbound FHIR webhook: method={} uri={} remoteAddr={}",
                      request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            // Note: response body deliberately not logged to avoid PII leakage
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only apply to FHIR webhook path
        return !request.getRequestURI().startsWith("/fhir/webhook");
    }
}
