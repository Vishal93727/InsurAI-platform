package com.insurai.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The single security chain for the whole platform.
 *
 * <p><b>Identity.</b> The portal's login endpoints issue HS256 JWTs ({@code sub} = e-mail, {@code role} = one of
 * ADMIN, HR, AGENT, EMPLOYEE). Every module trusts those tokens: they are validated here by a standard Spring
 * Security resource server, and the {@code role} claim becomes the {@code ROLE_*} authority. The core module
 * additionally maps the same claim to its own roles (see {@code CallerResolver}).
 *
 * <p><b>Authorisation.</b> Rules are evaluated top to bottom and the chain ends in {@code denyAll()}, so a new
 * endpoint is closed until someone opens it on purpose. The portal controllers also check roles inside the
 * method; those checks are kept as defence in depth.
 *
 * <p>The {@link JwtDecoder} is a bean (not built inline) so tests can replace it with their own key.
 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfig {

    @Bean
    JwtDecoder platformJwtDecoder(PlatformSecurityProperties props) {
        SecretKey key = new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwt().issuer()));
        return decoder;
    }

    @Bean
    SecurityFilterChain platformFilterChain(HttpSecurity http, PlatformSecurityProperties props, ObjectMapper json)
            throws Exception {

        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    // ---- infrastructure -------------------------------------------------------------------
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll();
                    // Servlet error dispatches are authorised too; without this an uncaught exception in any
                    // controller would surface as 401/403 instead of the real 4xx/5xx status.
                    a.requestMatchers("/error").permitAll();

                    // ---- core API: any valid token; the role is resolved per request inside the module ----
                    a.requestMatchers("/v1/**").authenticated();

                    // ---- portal: public entry points ------------------------------------------------------
                    a.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/register", "/auth/forgot-password",
                            "/admin/login", "/hr/login", "/agent/login").permitAll();
                    a.requestMatchers(HttpMethod.POST, "/auth/reset-password/*").permitAll();
                    // Uploaded claim documents are opened by plain links/iframes, which cannot send a bearer header.
                    a.requestMatchers(HttpMethod.GET, "/api/files/**", "/uploads/**").permitAll();

                    // ---- portal: directory/reference data used by several roles --------------------------
                    a.requestMatchers(HttpMethod.GET, "/auth/employees", "/employee/policies", "/agent", "/hr",
                            "/agent/available", "/agent/availability/all").authenticated();

                    // ---- portal: role areas (most specific first) -----------------------------------------
                    a.requestMatchers(HttpMethod.POST, "/agent/register").hasRole("ADMIN");
                    a.requestMatchers("/agent/**").hasRole("AGENT");
                    a.requestMatchers("/admin/**").hasRole("ADMIN");
                    a.requestMatchers("/hr/**").hasRole("HR");
                    a.requestMatchers("/employee/**").hasRole("EMPLOYEE");
                    a.requestMatchers("/notifications/*/read", "/notifications/user/**").authenticated();
                    a.requestMatchers("/notifications/**").hasAnyRole("HR", "ADMIN");

                    // ---- fraud engine ---------------------------------------------------------------------
                    // /api/v1/admin/** is guarded by its own X-Admin-Token interceptor (service-to-service).
                    a.requestMatchers("/api/v1/admin/**").permitAll();
                    a.requestMatchers("/api/v1/transactions/**", "/api/v1/reports").hasRole("ADMIN");

                    // ---- developer conveniences, off unless explicitly enabled ----------------------------
                    if (props.openDevEndpoints()) {
                        a.requestMatchers("/dashboard.html", "/ws/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll();
                    }

                    // ---- everything else is closed --------------------------------------------------------
                    a.anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(publicPathAwareResolver())
                        .jwt(j -> j.jwtAuthenticationConverter(roleClaimConverter()))
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, json,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, json,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, json,
                                HttpStatus.FORBIDDEN, "forbidden", "Not allowed")));
        return http.build();
    }

    /** {@code role} claim -> {@code ROLE_<ROLE>}. Tokens without the claim (e.g. from another issuer) get none. */
    static JwtAuthenticationConverter roleClaimConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new java.util.ArrayList<>();
            String role = jwt.getClaimAsString("role");
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(Locale.ROOT)));
            }
            return authorities;
        });
        return converter;
    }

    /**
     * The frontend attaches whatever token is in local storage to every request, including login. A resource
     * server rejects an expired token with 401 <em>before</em> authorisation runs, even on a public path, which
     * would stop a user with a stale token from ever logging in again. For the public entry points the header is
     * therefore ignored rather than validated.
     */
    static BearerTokenResolver publicPathAwareResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        List<String> exact = List.of("/auth/login", "/auth/register", "/auth/forgot-password",
                "/admin/login", "/hr/login", "/agent/login");
        return (HttpServletRequest request) -> {
            String path = request.getServletPath();
            if (exact.contains(path) || path.startsWith("/auth/reset-password/")
                    || path.startsWith("/api/files/") || path.startsWith("/uploads/")) {
                return null;
            }
            return delegate.resolve(request);
        };
    }
}
