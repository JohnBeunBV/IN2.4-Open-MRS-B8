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

    @NotNull
    private ProviderType providerType;

    @NotBlank
    private String callbackToken;

    private String timezone = "UTC";
    private String language = "en";
    private String vaultCredentialsPath;
}
