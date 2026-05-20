package nl.avans.communicatiemodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync   // Required for @Async on FhirSubscriptionService.registerAllSubscriptions
@ConfigurationPropertiesScan
public class CommunicatieModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunicatieModuleApplication.class, args);
    }
}
