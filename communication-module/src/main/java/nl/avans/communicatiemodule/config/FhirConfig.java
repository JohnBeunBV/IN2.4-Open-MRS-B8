package nl.avans.communicatiemodule.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirConfig {

    /**
     * Singleton FHIR R4 context. Creating this is expensive, so it's a Spring bean.
     */
    @Bean
    public FhirContext fhirContext() {
        FhirContext ctx = FhirContext.forR4();
        // Silence noisy FHIR validation warnings in logs
        ctx.getParserOptions().setStripVersionsFromReferences(false);
        return ctx;
    }

    @Bean
    public IParser fhirJsonParser(FhirContext fhirContext) {
        return fhirContext.newJsonParser().setPrettyPrint(false);
    }
}
