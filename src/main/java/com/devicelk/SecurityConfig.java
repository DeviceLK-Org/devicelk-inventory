package com.devicelk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the inventory service — or rather, the deliberate absence of
 * it on this service's own port.
 * <p>
 * <b>This class exists to keep endpoints open, not to close them.</b> That reads
 * backwards, and the reason is worth stating plainly: Spring Security is on the
 * classpath, and its mere presence authenticates every request and enables CSRF by
 * default. Without the chain below, adding the dependency would silently return
 * 401 to DeviceLK-AIRetrieval and 403 to the admin portal's writes — breakages
 * with no code change behind them.
 * <p>
 * <b>Why the authenticated chain went away.</b> While cart and order lived here,
 * this class carried a second, higher-priority chain requiring a Keycloak JWT on
 * {@code /api/v1/cart/**} and {@code /api/v1/orders/**}. Those endpoints moved to
 * DeviceLK-Commerce, which took the chain with them — and, having no public
 * surface of its own, inverted it into authenticated-by-default. Nothing this
 * service exposes belongs to an individual user: a product and its stock level are
 * the same facts for everyone who asks.
 * <p>
 * <b>What actually protects the write endpoints.</b> Not this service. The admin
 * portal reaches {@code /inventory/**} through the API gateway, which validates
 * the Keycloak token and enforces {@code ROLE_ADMIN} before proxying. This
 * service's own port is expected to be unreachable from outside the cluster. That
 * is a real assumption rather than a defence, and it is the reason locking these
 * endpoints down is on the architecture-audit list — when that happens, this file
 * and {@code OpenEndpointsRegressionTest} change in the same commit.
 * <p>
 * The gRPC service on its own port is unaffected either way: it is a separate
 * Netty server, not part of the servlet filter chain, and the gRPC starter only
 * installs authentication when a {@code GrpcAuthenticationReader} bean exists —
 * there is none here.
 * <p>
 * Conditional on a servlet web application so the configuration is simply absent
 * in tests that run with no HTTP surface to protect.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * Leaves every endpoint as open as it was before Spring Security was on the
     * classpath.
     * <p>
     * CSRF is disabled for the same reason the chain exists at all: it defaults to
     * on, and would start rejecting the admin portal's POST, PUT and DELETE calls
     * to {@code /inventory/**} — requests that work today and are authenticated at
     * the gateway, not here.
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
