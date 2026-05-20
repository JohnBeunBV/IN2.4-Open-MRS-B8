package nl.avans.communicatiemodule.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that all critical configuration is present at startup.
 * Throws IllegalStateException if any required value is missing or still holds
 * a placeholder default, preventing the application from starting in a broken state.
 */
@Slf4j
@Component
public class StartupValidator {

    @Value("${app.security.webhook-token:}")
    private String webhookToken;

    @Value("${app.security.admin-password:}")
    private String adminPassword;

    @Value("${app.fhir.callback-base-url:}")
    private String fhirCallbackBaseUrl;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    /** Set to true only in test/dev profiles to skip validation. */
    @Value("${app.startup-validation.skip:false}")
    private boolean skipValidation;

    /** Placeholder values that indicate unconfigured secrets. */
    private static final List<String> PLACEHOLDER_VALUES = List.of(
            "dev-webhook-secret", "dev-key-swiftsend", "dev-user", "dev-pass",
            "your-api-key-here", "securepost-client-id", "securepost-secret-key",
            "legacylink-user", "legacylink-password", "asyncflow-api-key",
            "root-token", "admin", "commpass"
    );

    @PostConstruct
    public void validate() {
        if (skipValidation) {
            log.warn("Startup validation SKIPPED (app.startup-validation.skip=true) - do not use in production");
            return;
        }

        List<String> errors = new ArrayList<>();

        requireNonBlank(errors, "app.security.webhook-token", webhookToken);
        requireNonBlank(errors, "app.security.admin-password", adminPassword);
        requireNonBlank(errors, "app.fhir.callback-base-url", fhirCallbackBaseUrl);
        requireNonBlank(errors, "spring.datasource.url", datasourceUrl);
        requireNonBlank(errors, "spring.datasource.username", datasourceUsername);

        // Reject placeholder/dev values
        checkNotPlaceholder(errors, "app.security.webhook-token", webhookToken);
        checkNotPlaceholder(errors, "app.security.admin-password", adminPassword);

        if (!errors.isEmpty()) {
            String msg = "STARTUP VALIDATION FAILED - missing or insecure configuration:\n  - "
                         + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("Startup validation passed: all required configuration is present");
    }

    private void requireNonBlank(List<String> errors, String key, String value) {
        if (!StringUtils.hasText(value)) {
            errors.add(key + " is not set");
        }
    }

    private void checkNotPlaceholder(List<String> errors, String key, String value) {
        if (StringUtils.hasText(value) && PLACEHOLDER_VALUES.stream()
                .anyMatch(p -> p.equalsIgnoreCase(value.trim()))) {
            errors.add(key + " contains a dev/placeholder value - set a real secret via environment variable");
        }
    }
}
