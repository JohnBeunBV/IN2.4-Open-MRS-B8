package nl.avans.communicatiemodule.provider;

import nl.avans.communicatiemodule.config.ProviderProperties;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.messaging.NotificationMessage;
import nl.avans.communicatiemodule.provider.swiftsend.SwiftSendAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwiftSendAdapterTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private SwiftSendAdapter adapter;
    private ProviderProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProviderProperties();
        properties.setBaseUrl("http://localhost:1337");
        ProviderProperties.SwiftSendProps props = new ProviderProperties.SwiftSendProps();
        props.setPath("/swiftsend");
        props.setApiKey("test-api-key");
        properties.setSwiftsend(props);

        adapter = new SwiftSendAdapter(webClient, properties);
    }

    @Test
    void getProviderType_returnsSwiftSend() {
        assertThat(adapter.getProviderType()).isEqualTo(ProviderType.SWIFT_SEND);
    }

    @Test
    void send_returnsFailure_onException() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(mock(WebClient.RequestHeadersSpec.class));

        // Simulate connection refused
        doThrow(new RuntimeException("Connection refused"))
            .when(webClient).post();

        NotificationMessage msg = buildTestMessage();
        SendResult result = adapter.send(msg);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Connection refused");
    }

    private NotificationMessage buildTestMessage() {
        return new NotificationMessage(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ProviderType.SWIFT_SEND,
            "REMINDER_24H",
            "+31612345678",
            "Test Patient",
            "Polyclinic B, Room 12",
            Instant.now().plusSeconds(86400),
            "Please arrive 10 minutes early.",
            "en",
            "Europe/Amsterdam"
            0
        );
    }
}
