package nl.avans.communicatiemodule.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
public class WebhookTokenFilter extends OncePerRequestFilter {

    private final byte[] expectedTokenBytes;

    public WebhookTokenFilter(String globalWebhookToken) {
        this.expectedTokenBytes = globalWebhookToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        log.debug("Inbound FHIR webhook: method={} uri={} remoteAddr={}",
                  request.getMethod(), request.getRequestURI(), request.getRemoteAddr());

        String authHeader = request.getHeader("Authorization");
        if (!isValidToken(authHeader)) {
            log.warn("Webhook rejected — invalid or missing token. remoteAddr={}", request.getRemoteAddr());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("Unauthorized");
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/fhir/webhook");
    }

    /** Constant-time comparison prevents timing-based token enumeration. */
    private boolean isValidToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        byte[] received = authHeader.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(received, expectedTokenBytes);
    }
}
