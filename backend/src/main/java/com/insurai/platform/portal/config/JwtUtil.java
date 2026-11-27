package com.insurai.platform.portal.config;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.insurai.platform.security.PlatformSecurityProperties;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and reads the platform's JWTs.
 *
 * <p>The signing secret, lifetime and issuer come from {@code platform.security.jwt.*}. In the original portal
 * the secret was a constant in this file, which meant anyone who could read the repository could mint an admin
 * token for any deployment. The same key is used by the platform's resource server to validate tokens on every
 * module, so tokens issued here are accepted by the core and fraud APIs as well.
 */
@Component
public class JwtUtil {

    private final Key signingKey;
    private final long expirationMillis;
    private final String issuer;

    public JwtUtil(PlatformSecurityProperties props) {
        this.signingKey = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = props.jwt().ttl().toMillis();
        this.issuer = props.jwt().issuer();
    }

    // Generate JWT token
    public String generateToken(String email, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(email)
                .setIssuer(issuer)
                .claim("role", role)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expirationMillis))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    // Extract email from token
    public String extractEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public String extractUsername(String token) {
        return extractEmail(token);
    }

    public boolean validateToken(String token, String email) {
        try {
            String tokenEmail = extractEmail(token);
            return (tokenEmail.equals(email) && !isTokenExpired(token));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        Date expiration = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
        return expiration.before(new Date());
    }

    // Extract role from token
    public String extractRole(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("role", String.class);
    }
}
