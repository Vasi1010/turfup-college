package com.turfup.web;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What a load balancer, uptime check, or another website sees. */
class DeploymentEndpointsTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicAndReportsUpWithoutDetails() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/metrics")).andExpect(status().is4xxClientError());
    }

    @Test
    void crossOriginRequestsAreRefused() throws Exception {
        mvc.perform(get("/api/venues").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void sameOriginRequestsPass() throws Exception {
        // MockMvc requests are to http://localhost; the browser sends Origin even for same-origin POSTs.
        mvc.perform(get("/api/venues").header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk());
    }
}
