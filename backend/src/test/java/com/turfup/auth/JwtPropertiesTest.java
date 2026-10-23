package com.turfup.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void missingOrShortSecretStopsStartup() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("31-bytes-is-one-byte-too-short!", Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ttlDefaultsToSevenDays() {
        assertThat(new JwtProperties("x".repeat(32), null).ttl()).isEqualTo(Duration.ofDays(7));
    }
}
