package nl.avans.communicatiemodule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import nl.avans.communicatiemodule.domain.ProviderType;

@Data
public class OrganisationConfigDto {

    @NotBlank
    private String name;

    @NotBlank
    private String openmrsBaseUrl;

    /**
     * OpenMRS REST API credentials voor de poller.
     * Sla in productie op via Vault; gebruik hier defaults voor dev/test.
     */
    private String openmrsUsername = "admin";
    private String openmrsPassword = "Admin1234";

    @NotNull
    private ProviderType providerType;

    @NotBlank
    private String callbackToken;

    private String timezone = "UTC";
    private String language = "en";
    private String vaultCredentialsPath;
}
