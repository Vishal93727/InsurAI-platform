package com.insurai.platform.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * How a token becomes a core-module {@link Caller}. The staff table always wins; platform-issued tokens may
 * additionally carry a portal role that maps onto a core role; anyone else is a customer.
 */
class CallerResolverTest {

    private static final String ISSUER = "insurai-platform";
    private static final String SUBJECT = "someone@example.test";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt token(String issuer, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "HS256").subject(SUBJECT);
        if (issuer != null) {
            builder.claim("iss", issuer);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }

    private static Caller resolve(RoleLookup staff, Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return new CallerResolver(staff, ISSUER).resolveArgument(null, null, null, null);
    }

    private static final RoleLookup NOT_STAFF = subject -> Optional.empty();

    @Test
    @DisplayName("portal roles map onto core roles: ADMIN->MANAGER, HR->ASSESSOR, AGENT->AGENT, EMPLOYEE->CUSTOMER")
    void platformRolesMapToCoreRoles() {
        assertThat(resolve(NOT_STAFF, token(ISSUER, "ADMIN")).role()).isEqualTo(Role.MANAGER);
        assertThat(resolve(NOT_STAFF, token(ISSUER, "HR")).role()).isEqualTo(Role.ASSESSOR);
        assertThat(resolve(NOT_STAFF, token(ISSUER, "AGENT")).role()).isEqualTo(Role.AGENT);
        assertThat(resolve(NOT_STAFF, token(ISSUER, "EMPLOYEE")).role()).isEqualTo(Role.CUSTOMER);
        assertThat(resolve(NOT_STAFF, token(ISSUER, "hr")).role()).as("case-insensitive").isEqualTo(Role.ASSESSOR);
    }

    @Test
    @DisplayName("the subject is the token's subject")
    void subjectComesFromTheToken() {
        assertThat(resolve(NOT_STAFF, token(ISSUER, "EMPLOYEE")).subject()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("a role claim from any other issuer is ignored: an external IdP cannot grant itself staff rights")
    void foreignIssuerCannotGrantRoles() {
        assertThat(resolve(NOT_STAFF, token("https://idp.example.test", "ADMIN")).role()).isEqualTo(Role.CUSTOMER);
        assertThat(resolve(NOT_STAFF, token(null, "ADMIN")).role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    @DisplayName("a token with no role claim is a customer")
    void noRoleClaimIsACustomer() {
        assertThat(resolve(NOT_STAFF, token(ISSUER, null)).role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    @DisplayName("an explicit staff-table assignment wins over the token's role claim")
    void staffTableWins() {
        RoleLookup assigned = subject -> Optional.of(Role.AGENT);

        assertThat(resolve(assigned, token(ISSUER, "ADMIN")).role()).isEqualTo(Role.AGENT);
    }

    @Test
    @DisplayName("no authenticated subject is a programming error, not a silent customer")
    void unauthenticatedIsRejected() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> new CallerResolver(NOT_STAFF, ISSUER).resolveArgument(null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
