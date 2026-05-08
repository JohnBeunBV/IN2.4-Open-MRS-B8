package nl.avans.communicatiemodule.provider;

import nl.avans.communicatiemodule.messaging.NotificationMessage;

/**
 * Strategy interface for each messaging provider adapter.
 */
public interface MessagingProvider {

    /**
     * Send a notification message via this provider.
     *
     * @param message the notification to send
     * @return result indicating success/failure and provider reference
     */
    SendResult send(NotificationMessage message);

    /**
     * Returns the provider type this adapter handles.
     */
    nl.avans.communicatiemodule.domain.ProviderType getProviderType();
}
