package nl.avans.communicatiemodule.provider.legacylink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.config.ProviderProperties;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.provider.MessageTextBuilder;
import nl.avans.communicatiemodule.provider.MessagingProvider;
import nl.avans.communicatiemodule.provider.SendResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LegacyLink: Legacy SOAP API with HTTP Basic authentication.
 * Constructs a SOAP 1.1 envelope and POSTs it to the service endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyLinkAdapter implements MessagingProvider {

    private final WebClient webClient;
    private final ProviderProperties properties;

    private static final Pattern MESSAGE_ID_PATTERN =
            Pattern.compile("<messageId>([^<]+)</messageId>", Pattern.CASE_INSENSITIVE);

    @Override
    public ProviderType getProviderType() {
        return ProviderType.LEGACY_LINK;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        ProviderProperties.LegacyLinkProps cfg = properties.getLegacylink();
        String baseUrl = properties.getBaseUrl() + cfg.getPath();
        String credentials = Base64.getEncoder().encodeToString(
                (cfg.getUsername() + ":" + cfg.getPassword()).getBytes(StandardCharsets.UTF_8));

        String soapBody = buildSoapEnvelope(message.getRecipientPhone(), MessageTextBuilder.build(message));

        log.debug("LegacyLink: SOAP POST to={}", message.getRecipientPhone());

        try {
            String response = webClient.post()
                    .uri(baseUrl + "/SendMessage")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                    .header(HttpHeaders.CONTENT_TYPE, "text/xml; charset=UTF-8")
                    .header("SOAPAction", "\"SendMessage\"")
                    .bodyValue(soapBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                return SendResult.failure("LegacyLink returned empty SOAP response");
            }

            // Check for SOAP Fault
            if (response.contains("<faultcode>") || response.contains(":Fault>")) {
                log.error("LegacyLink SOAP Fault: {}", response);
                return SendResult.failure("SOAP Fault received", response);
            }

            // Extract messageId from response
            Matcher matcher = MESSAGE_ID_PATTERN.matcher(response);
            String msgId = matcher.find() ? matcher.group(1) : "unknown";

            log.info("LegacyLink: message sent, messageId={}", msgId);
            return SendResult.success(msgId, response);

        } catch (Exception ex) {
            log.error("LegacyLink send failed: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private String buildSoapEnvelope(String to, String messageText) {
        // Escape XML special characters in the message body
        String escapedTo   = escapeXml(to);
        String escapedBody = escapeXml(messageText);

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope
                xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                xmlns:msg="http://legacylink.example.com/messaging">
              <soapenv:Header/>
              <soapenv:Body>
                <msg:SendMessage>
                  <msg:recipient>%s</msg:recipient>
                  <msg:message>%s</msg:message>
                </msg:SendMessage>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(escapedTo, escapedBody);
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
