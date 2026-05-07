package nl.avans.communicatiemodule.provider;

import nl.avans.communicatiemodule.config.ProviderProperties;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.provider.asyncflow.AsyncFlowAdapter;
import nl.avans.communicatiemodule.provider.legacylink.LegacyLinkAdapter;
import nl.avans.communicatiemodule.provider.securepost.SecurePostAdapter;
import nl.avans.communicatiemodule.provider.swiftsend.SwiftSendAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MessagingProviderFactoryTest {

    private MessagingProviderFactory factory;
    private ProviderProperties props;

    @BeforeEach
    void setUp() {
        props = new ProviderProperties();
        props.setSwiftsend(new ProviderProperties.SwiftSendProps());
        props.setSecurepost(new ProviderProperties.SecurePostProps());
        props.setLegacylink(new ProviderProperties.LegacyLinkProps());
        props.setAsyncflow(new ProviderProperties.AsyncFlowProps());

        WebClient wc = mock(WebClient.class);

        List<MessagingProvider> providers = List.of(
            new SwiftSendAdapter(wc, props),
            new SecurePostAdapter(wc, props),
            new LegacyLinkAdapter(wc, props),
            new AsyncFlowAdapter(wc, props)
        );

        factory = new MessagingProviderFactory(providers);
        factory.init();
    }

    @Test
    void getProvider_returnsSwiftSend() {
        assertThat(factory.getProvider(ProviderType.SWIFT_SEND))
            .isInstanceOf(SwiftSendAdapter.class);
    }

    @Test
    void getProvider_returnsSecurePost() {
        assertThat(factory.getProvider(ProviderType.SECURE_POST))
            .isInstanceOf(SecurePostAdapter.class);
    }

    @Test
    void getProvider_returnsLegacyLink() {
        assertThat(factory.getProvider(ProviderType.LEGACY_LINK))
            .isInstanceOf(LegacyLinkAdapter.class);
    }

    @Test
    void getProvider_returnsAsyncFlow() {
        assertThat(factory.getProvider(ProviderType.ASYNC_FLOW))
            .isInstanceOf(AsyncFlowAdapter.class);
    }

    @Test
    void getProvider_throwsForUnknownType() {
        // Remove one provider and verify factory throws
        MessagingProviderFactory emptyFactory = new MessagingProviderFactory(List.of());
        emptyFactory.init();
        assertThatThrownBy(() -> emptyFactory.getProvider(ProviderType.SWIFT_SEND))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
