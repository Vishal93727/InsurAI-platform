package com.insurai.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import com.insurai.platform.portal.config.JwtUtil;

/**
 * The identity contract of the platform: the portal mints tokens with JJWT, every module validates them with
 * Spring Security's Nimbus decoder. If these two ever disagree (algorithm, key encoding, claim names) every
 * authenticated call in every module fails, so the agreement is pinned here.
 */
class PlatformSecurityConfigTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-for-hs256";
    private static final String OTHER_SECRET = "a-completely-different-secret-also-long-enough";

    private static PlatformSecurityProperties props(String secret, Duration ttl, String issuer) {
        return new PlatformSecurityProperties(
                new PlatformSecurityProperties.JwtSettings(secret, ttl, issuer),
                new PlatformSecurityProperties.AdminAccount("admin@example.test", "password-123"),
                List.of(), false);
    }

    private final PlatformSecurityProperties platform = props(SECRET, Duration.ofHours(1), "insurai-platform");
    private final JwtDecoder decoder = new PlatformSecurityConfig().platformJwtDecoder(platform);

    // ---------------------------------------------------------------- token interoperability

    @Test
    @DisplayName("a token minted by the portal (JJWT) is accepted by the platform decoder (Nimbus)")
    void portalTokenIsAcceptedByPlatformDecoder() {
        String token = new JwtUtil(platform).generateToken("hr@example.test", "HR");

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("hr@example.test");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("HR");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("insurai-platform");
    }

    @Test
    @DisplayName("the portal can still read back what it issued")
    void portalCanReadItsOwnTokens() {
        JwtUtil util = new JwtUtil(platform);
        String token = util.generateToken("agent@example.test", "AGENT");

        assertThat(util.extractEmail(token)).isEqualTo("agent@example.test");
        assertThat(util.extractRole(token)).isEqualTo("AGENT");
        assertThat(util.validateToken(token, "agent@example.test")).isTrue();
        assertThat(util.validateToken(token, "someone-else@example.test")).isFalse();
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = new JwtUtil(props(OTHER_SECRET, Duration.ofHours(1), "insurai-platform"))
                .generateToken("admin@example.test", "ADMIN");

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        String expired = new JwtUtil(props(SECRET, Duration.ofMinutes(-10), "insurai-platform"))
                .generateToken("hr@example.test", "HR");

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected even if the key matches")
    void tokenFromAnotherIssuerIsRejected() {
        String foreign = new JwtUtil(props(SECRET, Duration.ofHours(1), "someone-else"))
                .generateToken("hr@example.test", "HR");

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    // ---------------------------------------------------------------- role claim -> authority

    @Test
    @DisplayName("the role claim becomes a ROLE_ authority, whatever its case")
    void roleClaimBecomesAuthority() {
        JwtAuthenticationConverter converter = PlatformSecurityConfig.roleClaimConverter();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("x@example.test").claim("role", "hr").build();

        List<String> authorities = converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactly("ROLE_HR");
    }

    @Test
    @DisplayName("a token without a role claim gets no authorities")
    void noRoleClaimMeansNoAuthorities() {
        JwtAuthenticationConverter converter = PlatformSecurityConfig.roleClaimConverter();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("x@example.test").build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    // ---------------------------------------------------------------- stale tokens on public endpoints

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.addHeader("Authorization", "Bearer stale-token");
        return request;
    }

    @Test
    @DisplayName("login and other public entry points ignore a stale bearer token instead of failing with 401")
    void publicEntryPointsIgnoreTheAuthorizationHeader() {
        BearerTokenResolver resolver = PlatformSecurityConfig.publicPathAwareResolver();

        for (String path : List.of("/auth/login", "/auth/register", "/auth/forgot-password", "/admin/login",
                "/hr/login", "/agent/login", "/auth/reset-password/abc123", "/api/files/view/x.pdf")) {
            assertThat(resolver.resolve(request("POST", path))).as(path).isNull();
        }
    }

    @Test
    @DisplayName("every other path still gets its bearer token validated")
    void protectedPathsKeepTheirToken() {
        BearerTokenResolver resolver = PlatformSecurityConfig.publicPathAwareResolver();

        for (String path : List.of("/v1/me", "/admin/policies", "/hr/claims", "/auth/employees", "/api/v1/transactions")) {
            assertThat(resolver.resolve(request("GET", path))).as(path).isEqualTo("stale-token");
        }
    }
}
