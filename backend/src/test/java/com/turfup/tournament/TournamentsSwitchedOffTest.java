package com.turfup.tournament;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With turfup.tournaments.enabled off (the default), the tournament endpoints don't exist. */
class TournamentsSwitchedOffTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void theEndpointsAnswerNotFound() throws Exception {
        mvc.perform(get("/api/matches/1")).andExpect(status().isNotFound());
        long admin = createUser("9000000001", "ADMIN");
        mvc.perform(post("/api/matches/1/toss").header(HttpHeaders.AUTHORIZATION, bearer(admin, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"winnerId\": 1, \"decision\": \"BAT\"}"))
                .andExpect(status().isNotFound());
    }
}
