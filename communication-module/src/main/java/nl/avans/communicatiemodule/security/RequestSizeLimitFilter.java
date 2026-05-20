package nl.avans.communicatiemodule.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Rejects requests whose Content-Length exceeds a configured maximum.
 * Protects the webhook endpoint from oversized payload attacks.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestSizeLimitFilter implements Filter {

    private final int maxBodyBytes;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        int contentLength = request.getContentLength();
        if (contentLength > maxBodyBytes) {
            log.warn("Rejected oversized webhook request: contentLength={} max={} remoteAddr={}",
                     contentLength, maxBodyBytes, request.getRemoteAddr());
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.getWriter().write("Payload too large");
            return;
        }

        chain.doFilter(req, res);
    }
}
