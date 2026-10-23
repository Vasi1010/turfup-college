package com.turfup.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@code turfup.jwt.*}: HS256 signing secret (at least 32 bytes) and token lifetime. */
@ConfigurationProperties("turfup.jwt")
public record JwtProperties(String secret, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "turfup.jwt.secret is missing or shorter than 32 bytes. Set the JWT_SECRET environment variable.");
        }
        if (ttl == null) {
            ttl = Duration.ofDays(7);
        }
    }
}
