package com.devicelk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the commerce monolith.
 * <p>
 * Lives in the application's root package rather than inside a module: it
 * governs every inbound request, so filing it under {@code cart} would both
 * misrepresent its scope and make Spring Modulith treat an application-wide
 * concern as one module's property. Keeping it beside
 * {@link DeviceLkCommerceApplication} also avoids inventing a {@code config}
 * package that Modulith would detect as a module of its own.
 * <p>
 * <b>Two chains, and the second one is not optional.</b> Putting Spring Security
 * on the classpath secures every endpoint by default and turns on CSRF — which
 * would have locked down the inventory REST API that the admin portal and the
 * AI retrieval service already use, and rejected their writes. The cart chain
 * therefore matches only the cart paths, and a second, lower-priority chain
 * explicitly restores open access to everything else, leaving those callers
 * exactly as they were.
 * <p>
 * The gRPC service on its own port is unaffected: it is a separate Netty server,
 * not part of the servlet filter chain, and the gRPC starter only installs
 * authentication when a {@code GrpcAuthenticationReader} bean exists — there is
 * none here.
 * <p>
 * Conditional on a servlet web application because {@code securityMatcher} builds
 * an {@code MvcRequestMatcher}, which needs Spring MVC's
 * {@code HandlerMappingIntrospector} and therefore a servlet context. The
 * alternative — matching paths with {@code AntPathRequestMatcher} instead — would
 * make the config context-agnostic at the cost of using a different path-matching
 * algorithm from the one Spring MVC uses to dispatch, which is how request-matcher
 * bypasses happen. Keeping the MVC matcher and skipping the config where there is
 * no HTTP surface to protect is the safer half of that trade.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /** Path prefix for everything the cart module exposes. */
    private static final String CART_PATHS = "/api/v1/cart/**";

    /**
     * Requires a valid Keycloak JWT on the cart endpoints.
     * <p>
     * Resource server, not login: this application never redirects anyone to
     * Keycloak or holds a session. It takes a bearer token, checks the signature
     * against the realm's JWKS and the {@code iss} claim against the configured
     * issuer, and derives the user from it. A request without a usable token is
     * rejected by the filter chain before any cart code runs — which is what
     * makes {@code CartController}'s subject non-negotiable.
     * <p>
     * Stateless and CSRF-free because the credential is a header the browser
     * does not attach automatically. CSRF defends against the ambient authority
     * of a cookie; a bearer token has none, and enabling it here would only
     * break every non-browser client.
     */
    @Bean
    @Order(1)
    SecurityFilterChain cartSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(CART_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Leaves every other endpoint exactly as open as it was before security
     * existed in this application.
     * <p>
     * This chain is a deliberate no-op, not an oversight. The inventory REST API,
     * the actuator endpoints and the fallback routes were unauthenticated, and
     * several live callers depend on that; tightening them is a decision to make
     * on purpose, with those callers updated in the same change, rather than a
     * side effect of the cart module gaining a login.
     * <p>
     * CSRF is disabled here for the same reason: it defaults to on with Spring
     * Security present, and would start rejecting the admin portal's POST, PUT
     * and DELETE calls to {@code /inventory/**} — requests that work today.
     */
    @Bean
    @Order(2)
    SecurityFilterChain openEndpointsFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
