package nl.avans.communicatiemodule.provider.legacylink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.config.ProviderProperties;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.provider.MessageTextBuilder;
import nl.avans.communicatiemodule.provider.MessagingProvider;
import nl.avans.communicatiemodule.provider.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyLinkAdapter implements MessagingProvider {

    private static final String STUDENT_GROUP = "groep-8";
    private static final String NAMESPACE     = "http://legacylink.fakecomworld.com/v1";
    private static final Pattern REF_PATTERN  =
            Pattern.compile("<MessageReference>([^<]+)</MessageReference>", Pattern.CASE_INSENSITIVE);

    private final WebClient webClient;
    private final ProviderProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.LEGACY_LINK;
    }

    @Override
    public SendResult send(NotificationMessage message) {
        ProviderProperties.LegacyLinkProps cfg = properties.getLegacylink();
        String url         = properties.getBaseUrl() + cfg.getPath() + "/SendSms";
        String credentials = Base64.getEncoder().encodeToString(
                (cfg.getUsername() + ":" + cfg.getPassword()).getBytes(StandardCharsets.UTF_8));

        // SMS max 160 tekens
        String text = MessageTextBuilder.build(message);
        if (text.length() > 160) text = text.substring(0, 157) + "...";

        String xml = buildXml(message.getRecipientPhone(), text);

        log.debug("LegacyLink: POST {} to={}", url, message.getRecipientPhone());

        try {
            String response = webClient.post()
                    .uri(url)
                    .header("Authorization", "Basic " + credentials)
                    .header("X-STUDENT-GROUP", STUDENT_GROUP)
                    .header("Content-Type", "application/xml")
                    .header("Accept", "application/xml")
                    .bodyValue(xml)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                return SendResult.failure("LegacyLink: lege response");
            }

            if (response.contains("<StatusCode>401") || response.contains("<StatusCode>400")
                    || response.contains("<StatusCode>500")) {
                log.error("LegacyLink fout: {}", response);
                return SendResult.failure("LegacyLink fout in response", response);
            }

            Matcher matcher = REF_PATTERN.matcher(response);
            String ref = matcher.find() ? matcher.group(1) : "unknown";

            log.info("LegacyLink: SMS verstuurd, ref={}", ref);
            return SendResult.success(ref, response);

        } catch (Exception ex) {
            log.error("LegacyLink send mislukt: {}", ex.getMessage());
            return SendResult.failure(ex.getMessage());
        }
    }

    private String buildXml(String phone, String text) {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <SendSmsRequest xmlns="%s">
              <PhoneNumber>%s</PhoneNumber>
              <MessageText>%s</MessageText>
              <SenderIdentification>OpenMRS</SenderIdentification>
            </SendSmsRequest>
            """.formatted(NAMESPACE, escapeXml(phone), escapeXml(text));
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