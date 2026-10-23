package com.turfup.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Listed origins (the hosted demo's Vercel site) may call the API and read the server's Date header. */
class CorsConfigTest {

    private static CorsConfiguration configFor(List<String> origins) {
        return new SecurityConfig().corsConfigurationSource(origins)
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/venues"));
    }

    @Test
    void listedOriginIsAllowedAndSeesTheDateHeader() {
        CorsConfiguration config = configFor(List.of(" https://turfup-nine.vercel.app ", ""));
        assertThat(config.checkOrigin("https://turfup-nine.vercel.app")).isEqualTo("https://turfup-nine.vercel.app");
        assertThat(config.checkOrigin("https://evil.example")).isNull();
        assertThat(config.getExposedHeaders()).containsExactly("Date");
    }

    @Test
    void noOriginsListedMeansNoneAllowed() {
        assertThat(configFor(List.of()).checkOrigin("https://turfup-nine.vercel.app")).isNull();
    }
}
