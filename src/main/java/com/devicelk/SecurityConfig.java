package com.devicelk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the inventory service — specifically, keeping its endpoints
 * open rather than closing them.
 * <p>
 * Spring Security is on the classpath, and its presence alone authenticates every
 * request and enables CSRF. Without the chain below, that would return 401 to
 * DeviceLK-AIRetrieval and 403 to the admin portal's writes, with no code change
 * behind the breakage. Nothing this service exposes belongs to an individual
 * user: a product and its stock level are the same facts for everyone.
 * <p>
 * The write endpoints are protected by the API gateway, which validates the
 * Keycloak token and enforces {@code ROLE_ADMIN} before proxying; this service's
 * own port is assumed unreachable from outside the cluster. That is an assumption
 * rather than a defence, and locking these endpoints down is on the
 * architecture-audit list — this file and {@code OpenEndpointsRegressionTest}
 * would change together.
 * <p>
 * The gRPC server is unaffected: it is a separate Netty server outside the
 * servlet filter chain, and the gRPC starter only adds authentication when a
 * {@code GrpcAuthenticationReader} bean exists.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * Leaves every endpoint as open as it was before Spring Security was on the
     * classpath. CSRF is disabled for the same reason: on by default, it would
     * reject the admin portal's writes to {@code /inventory/**}, which are
     * authenticated at the gateway rather than here.
     */
    @Bean
    SecurityFilterChain openEndpointsFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
