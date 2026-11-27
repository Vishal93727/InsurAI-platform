package com.insurai.platform.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Platform-wide identity settings. There are deliberately no defaults for secrets here: the {@code local}
 * profile supplies development values, every other profile must provide real ones or the application refuses
 * to start.
 *
 * @param jwt              signing settings for the tokens the portal issues and every module accepts
 * @param admin            the bootstrap administrator account
 * @param corsAllowedOrigins origin patterns allowed to call the API from a browser
 * @param openDevEndpoints when true, the fraud dashboard, its WebSocket and Swagger UI are reachable without a
 *                         token. Intended for local development only.
 */
@Validated
@ConfigurationProperties("platform.security")
public record PlatformSecurityProperties(
        @Valid @NotNull JwtSettings jwt,
        @Valid @NotNull AdminAccount admin,
        List<String> corsAllowedOrigins,
        boolean openDevEndpoints) {

    public PlatformSecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    /** HS256 key material. 32 characters is the minimum for a 256-bit key. */
    public record JwtSettings(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256") String secret,
            Duration ttl,
            String issuer) {

        public JwtSettings {
            ttl = ttl == null ? Duration.ofHours(24) : ttl;
            issuer = issuer == null || issuer.isBlank() ? "insurai-platform" : issuer;
        }
    }

    public record AdminAccount(
            @NotBlank String email,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password) {
    }
}
