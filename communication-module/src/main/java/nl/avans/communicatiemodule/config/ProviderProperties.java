package nl.avans.communicatiemodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.providers")
public class ProviderProperties {

    private String baseUrl = "http://localhost:1337";

    private SwiftSendProps swiftsend = new SwiftSendProps();
    private SecurePostProps securepost = new SecurePostProps();
    private LegacyLinkProps legacylink = new LegacyLinkProps();
    private AsyncFlowProps asyncflow = new AsyncFlowProps();

    @Data
    public static class SwiftSendProps {
        private String path = "/swiftsend";
        private String apiKey;
    }

    @Data
    public static class SecurePostProps {
        private String path = "/securepost";
        private String username;
        private String password;
    }

    @Data
    public static class LegacyLinkProps {
        private String path = "/legacylink";
        private String username;
        private String password;
    }

    @Data
    public static class AsyncFlowProps {
        private String path = "/asyncflow";
        private String apiKey;
        private long pollIntervalMs = 3000;
        private int maxPollAttempts = 10;
    }
}
