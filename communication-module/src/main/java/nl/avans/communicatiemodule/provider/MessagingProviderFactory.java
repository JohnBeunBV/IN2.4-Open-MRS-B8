package nl.avans.communicatiemodule.provider;

import lombok.RequiredArgsConstructor;
import nl.avans.communicatiemodule.domain.ProviderType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MessagingProviderFactory {

    private final List<MessagingProvider> providers;

    private Map<ProviderType, MessagingProvider> providerMap;

    @jakarta.annotation.PostConstruct
    void init() {
        providerMap = providers.stream()
                .collect(Collectors.toMap(MessagingProvider::getProviderType, Function.identity()));
    }

    public MessagingProvider getProvider(ProviderType type) {
        MessagingProvider provider = providerMap.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("No provider adapter registered for type: " + type);
        }
        return provider;
    }
}
