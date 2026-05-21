package nl.avans.communicatiemodule.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fails fast at startup if critical configuration is missing or contains placeholder values.
 * Disable in tests via app.startup-validation.skip=true.
 */
@Slf4j
@Component
public class StartupValidator {

    private static final Set<String> PLACEHOLDERS = Set.of(
        "dev-webhook-secret", "your-api-key-here", "dev-key-swiftsend",
        "securepost-client-id", "securepost-secret-key", "legacylink-user",
        "legacylink-password", "asyncflow-api-key", "root-token", "admin", "commpass"
    );

    @Value("${app.security.webhook-token:}") private String webhookToken;
    @Value("${app.security.admin-password:}") private String adminPassword;
    @Value("${app.fhir.callback-base-url:}")  private String fhirCallbackBaseUrl;
    @Value("${spring.datasource.url:}")        private String datasourceUrl;
    @Value("${app.startup-validation.skip:false}") private boolean skip;

    @PostConstruct
    public void validate() {
        if (skip) {
            log.warn("Startup validation SKIPPED — do not use in production");
            return;
        }

        List<String> errors = new ArrayList<>();
        requireSet(errors, "app.security.webhook-token", webhookToken);
        requireSet(errors, "app.security.admin-password", adminPassword);
        requireSet(errors, "app.fhir.callback-base-url", fhirCallbackBaseUrl);
        requireSet(errors, "spring.datasource.url", datasourceUrl);
        rejectPlaceholder(errors, "app.security.webhook-token", webhookToken);
        rejectPlaceholder(errors, "app.security.admin-password", adminPassword);

        if (!errors.isEmpty()) {
            String msg = "STARTUP VALIDATION FAILED:\n  - " + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("Startup validation passed");
    }

    private void requireSet(List<String> errors, String key, String value) {
        if (!StringUtils.hasText(value)) errors.add(key + " is not configured");
    }

    private void rejectPlaceholder(List<String> errors, String key, String value) {
        if (StringUtils.hasText(value) && PLACEHOLDERS.contains(value.trim()))
            errors.add(key + " contains a placeholder/dev value — set a real secret");
    }
}
