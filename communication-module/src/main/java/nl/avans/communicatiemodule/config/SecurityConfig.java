package nl.avans.communicatiemodule.config;

import nl.avans.communicatiemodule.security.RequestSizeLimitFilter;
import nl.avans.communicatiemodule.security.WebhookTokenFilter;
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

    @Value("${app.security.webhook-token}")
    private String webhookToken;

    /** No default — must be set via ADMIN_PASSWORD env var. */
    @Value("${app.security.admin-password}")
    private String adminPassword;

    /** Max webhook body size in bytes (default 1 MB). */
    @Value("${app.security.max-webhook-body-bytes:1048576}")
    private int maxWebhookBodyBytes;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/fhir/webhook/**").permitAll()
                .requestMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(webhookTokenFilter(), UsernamePasswordAuthenticationFilter.class)
            .httpBasic(basic -> {});

        return http.build();
    }

    @Bean
    public WebhookTokenFilter webhookTokenFilter() {
        return new WebhookTokenFilter(webhookToken);
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        FilterRegistrationBean<RequestSizeLimitFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new RequestSizeLimitFilter(maxWebhookBodyBytes));
        reg.addUrlPatterns("/fhir/webhook/*");
        reg.setOrder(1);
        return reg;
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
