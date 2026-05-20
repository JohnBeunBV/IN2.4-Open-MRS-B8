package nl.avans.communicatiemodule.config;

import io.micrometer.core.instrument.MeterRegistry;
import nl.avans.communicatiemodule.security.WebhookTokenFilter;
import nl.avans.communicatiemodule.security.RequestSizeLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Must be set via env var WEBHOOK_TOKEN; no dev-default in production. */
    @Value("${app.security.webhook-token}")
    private String webhookToken;

    /** Must be set via env var ADMIN_PASSWORD; no dev-default in production. */
    @Value("${app.security.admin-password}")
    private String adminPassword;

    /** Maximum request body size for the webhook endpoint (1 MB default). */
    @Value("${app.security.max-webhook-body-bytes:1048576}")
    private int maxWebhookBodyBytes;

    private final MeterRegistry meterRegistry;

    public SecurityConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public: actuator health + Prometheus
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                // FHIR webhook: token is enforced by WebhookTokenFilter before reaching here
                .requestMatchers("/fhir/webhook/**").permitAll()
                // Admin API requires ADMIN role
                .requestMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(webhookTokenFilter(), UsernamePasswordAuthenticationFilter.class)
            .httpBasic(basic -> {});

        return http.build();
    }

    @Bean
    public WebhookTokenFilter webhookTokenFilter() {
        return new WebhookTokenFilter(webhookToken, meterRegistry);
    }

    /**
     * Enforce a maximum request-body size for the webhook endpoint
     * to protect against oversized payload attacks.
     */
    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestSizeLimitFilter(maxWebhookBodyBytes));
        registration.addUrlPatterns("/fhir/webhook/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder().encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
