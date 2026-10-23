package com.turfup.auth;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTest extends PostgresTestBase {

    private static final String HOLD_BODY = """
            {"courtId": %d, "startAt": "2030-01-15T20:00:00+05:30", "endAt": "2030-01-15T21:00:00+05:30"}
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void registeredPlayerCanUseTheirTokenToHoldASlot() throws Exception {
        long courtId = createCourt();
        addStandardPriceRules(courtId);

        String body = register("Ravi", "9876543210", "secret-pass", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andExpect(jsonPath("$.name").value("Ravi"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");

        mvc.perform(post("/api/bookings/holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOLD_BODY.formatted(courtId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void ownerCanRegister() throws Exception {
        register("Imran", "8876543210", "secret-pass", "OWNER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void cannotRegisterAsAdmin() throws Exception {
        register("Mallory", "9876543210", "secret-pass", "ADMIN")
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePhoneIsRejected() throws Exception {
        register("Ravi", "9876543210", "secret-pass", null).andExpect(status().isCreated());

        register("Someone Else", "9876543210", "other-pass", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void invalidPhoneAndShortPasswordAreRejected() throws Exception {
        register("Ravi", "12345", "secret-pass", null).andExpect(status().isBadRequest());
        register("Ravi", "9876543210", "short", null).andExpect(status().isBadRequest());
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() throws Exception {
        register("Ravi", "9876543210", "secret-pass", null);

        login("9876543210", "secret-pass")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("PLAYER"));
    }

    @Test
    void wrongPasswordAndUnknownPhoneGetTheSame401() throws Exception {
        register("Ravi", "9876543210", "secret-pass", null);

        login("9876543210", "wrong-pass")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("9000000999", "secret-pass")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointsNeedAValidToken() throws Exception {
        mvc.perform(post("/api/bookings/holds").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY.formatted(1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        String forged = tokens.issue(1, Role.OWNER) + "x";
        mvc.perform(get("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforcedByArea() throws Exception {
        long player = createPlayer(1);
        long owner = createUser("8000000999", "OWNER");

        mvc.perform(get("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, bearer(player, Role.PLAYER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/holds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOLD_BODY.formatted(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void slotsEndpointIsPublic() throws Exception {
        long courtId = createCourt();
        addStandardPriceRules(courtId);

        mvc.perform(get("/api/courts/{id}/slots", courtId).param("date", "2030-01-15"))
                .andExpect(status().isOk());
    }

    private ResultActions register(String name, String phone, String password, String role) throws Exception {
        String roleJson = role == null ? "" : ", \"role\": \"%s\"".formatted(role);
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "phone": "%s", "password": "%s"%s}
                        """.formatted(name, phone, password, roleJson)));
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phone": "%s", "password": "%s"}
                        """.formatted(phone, password)));
    }
}
