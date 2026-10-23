package com.turfup.venue;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Owner venue and court setup through the API. Opening hours 6 AM to 2 AM (360..1560) unless stated. */
class VenueSetupTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final String STANDARD_RULES = """
            [{"startMinute": 360, "endMinute": 1020, "rate": 80000},
             {"startMinute": 1020, "endMinute": 1560, "rate": 100000}]
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService bookingService;

    long owner;

    @BeforeEach
    void setUp() {
        owner = createUser("8000000999", "OWNER");
    }

    @Test
    void ownerCreatesAVenueWithCourtsAndOnceApprovedEachIsBookable() throws Exception {
        String body = create(360, 1560, court("Main turf", "[\"football\", \"cricket\"]", 60, STANDARD_RULES),
                court("Badminton 1", "[\"badminton\"]", 30, STANDARD_RULES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Green Arena"))
                .andExpect(jsonPath("$.openMinute").value(360))
                .andExpect(jsonPath("$.closeMinute").value(1560))
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.courts.length()").value(2))
                .andExpect(jsonPath("$.courts[0].name").value("Main turf"))
                .andExpect(jsonPath("$.courts[0].sports[0]").value("football"))
                .andExpect(jsonPath("$.courts[0].sports[1]").value("cricket"))
                .andExpect(jsonPath("$.courts[0].advanceAmount").value(20000))
                .andExpect(jsonPath("$.courts[0].priceRules.length()").value(2))
                .andExpect(jsonPath("$.courts[1].slotMinutes").value(30))
                .andReturn().getResponse().getContentAsString();
        long venueId = ((Number) JsonPath.read(body, "$.id")).longValue();
        long mainTurf = ((Number) JsonPath.read(body, "$.courts[0].id")).longValue();
        long badminton = ((Number) JsonPath.read(body, "$.courts[1].id")).longValue();

        assertThat(ownerOf(mainTurf)).isEqualTo(owner);
        jdbc.sql("UPDATE venues SET approved = true WHERE id = :id").param("id", venueId).update();
        mvc.perform(get("/api/courts/{id}/slots", mainTurf).param("date", "2030-01-15"))
                .andExpect(jsonPath("$.slots.length()").value(20))
                .andExpect(jsonPath("$.slots[11].price").value(100000));
        mvc.perform(get("/api/courts/{id}/slots", badminton).param("date", "2030-01-15"))
                .andExpect(jsonPath("$.slots.length()").value(40));
        mvc.perform(get("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(venueId));
    }

    @Test
    void aVenueNeedsACourtAndEveryCourtASport() throws Exception {
        mvc.perform(post("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venue\": %s, \"courts\": []}".formatted(venue(360, 1560))))
                .andExpect(status().isBadRequest());
        create(360, 1560, court("Main", "[]", 60, STANDARD_RULES)).andExpect(status().isBadRequest());
        create(360, 1560, court("Main", "[\"kabaddi\"]", 60, STANDARD_RULES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VENUE_SETUP"))
                .andExpect(jsonPath("$.detail").value("Unknown sport: kabaddi."));

        assertThat(venueCount()).isZero();
    }

    @Test
    void dayRulesMayCoverPartOfTheDay() throws Exception {
        create(360, 1560, court("Main", "[\"football\"]", 60, """
                [{"startMinute": 360, "endMinute": 1020, "rate": 80000},
                 {"startMinute": 1020, "endMinute": 1560, "rate": 100000},
                 {"dayOfWeek": 6, "startMinute": 1080, "endMinute": 1320, "rate": 150000}]
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courts[0].priceRules.length()").value(3));
    }

    @Test
    void gapInGeneralRulesIsRejected() throws Exception {
        create(360, 1560, court("Main", "[\"football\"]", 60, """
                [{"startMinute": 360, "endMinute": 960, "rate": 80000},
                 {"startMinute": 1020, "endMinute": 1560, "rate": 100000}]
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VENUE_SETUP"))
                .andExpect(jsonPath("$.detail").value(containsString("from 16:00")));

        assertThat(venueCount()).isZero();
    }

    @Test
    void generalRulesMustCoverOpeningToClosing() throws Exception {
        create(360, 1560, court("Main", "[\"football\"]", 60, "[{\"startMinute\": 360, \"endMinute\": 1440, \"rate\": 80000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("from 00:00 (next day)")));
        create(360, 1560, court("Main", "[\"football\"]", 60, "[{\"startMinute\": 420, \"endMinute\": 1560, \"rate\": 80000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("from 06:00")));
        create(360, 1560, court("Main", "[\"football\"]", 60,
                "[{\"dayOfWeek\": 6, \"startMinute\": 360, \"endMinute\": 1560, \"rate\": 80000}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlappingRulesOfTheSameKindAreRejected() throws Exception {
        create(360, 1560, court("Main", "[\"football\"]", 60, """
                [{"startMinute": 360, "endMinute": 1080, "rate": 80000},
                 {"startMinute": 1020, "endMinute": 1560, "rate": 100000}]
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("overlap")));
        create(360, 1560, court("Main", "[\"football\"]", 60, """
                [{"startMinute": 360, "endMinute": 1560, "rate": 80000},
                 {"dayOfWeek": 6, "startMinute": 1020, "endMinute": 1200, "rate": 100000},
                 {"dayOfWeek": 6, "startMinute": 1140, "endMinute": 1560, "rate": 100000}]
                """))
                .andExpect(status().isBadRequest());

        assertThat(venueCount()).isZero();
    }

    @Test
    void inconsistentHoursAreRejected() throws Exception {
        create(1200, 360, court("Main", "[\"football\"]", 60, STANDARD_RULES)).andExpect(status().isBadRequest());
        create(360, 1860, court("Main", "[\"football\"]", 60, STANDARD_RULES)).andExpect(status().isBadRequest()); // > 24 h
        create(1440, 1560, court("Main", "[\"football\"]", 60, STANDARD_RULES)).andExpect(status().isBadRequest());

        assertThat(venueCount()).isZero();
    }

    @Test
    void addingACourtNeedsAFreeNameAndRulesThatFitTheVenue() throws Exception {
        long courtId = ownedCourt();
        long venueId = venueOf(courtId);

        addCourt(venueId, court("Court 1", "[\"football\"]", 60, STANDARD_RULES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Another court here is already called Court 1."));
        addCourt(venueId, court("Badminton", "[\"badminton\"]", 60, "[{\"startMinute\": 360, \"endMinute\": 1000, \"rate\": 1}]"))
                .andExpect(status().isBadRequest());
        addCourt(venueId, court("Badminton", "[\"badminton\"]", 60, STANDARD_RULES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courts.length()").value(2))
                .andExpect(jsonPath("$.courts[1].name").value("Badminton"))
                .andExpect(jsonPath("$.courts[1].sortOrder").value(1));
    }

    @Test
    void changingVenueHoursIsRefusedIfACourtWouldNoLongerFit() throws Exception {
        long courtId = ownedCourt(); // general rules cover the whole operating day (0..2880)
        long venueId = venueOf(courtId);
        long narrow = addCourt(venueId, "Badminton", 60, "badminton");
        addPriceRule(narrow, null, 360, 1560, 40_000);

        save(put("/api/owner/venues/{id}", venueId), venue(300, 1560))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Badminton: No general price is set from 05:00")));
        save(put("/api/owner/venues/{id}", venueId), venue(420, 1500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openMinute").value(420))
                .andExpect(jsonPath("$.courts.length()").value(2));
    }

    @Test
    void updatingACourtReplacesItsRulesButExistingBookingsKeepTheirPrice() throws Exception {
        long courtId = ownedCourt();
        OffsetDateTime eightPm = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
        Booking before = bookingService.hold(courtId, createPlayer(1), eightPm, eightPm.plusHours(1));

        save(put("/api/owner/courts/{id}", courtId), court("Court 1", "[\"football\"]", 60,
                "[{\"startMinute\": 360, \"endMinute\": 1560, \"rate\": 120000}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courts[0].priceRules.length()").value(1));

        Booking after = bookingService.hold(courtId, createPlayer(2), eightPm.plusHours(1), eightPm.plusHours(2));
        assertThat(after.getTotalPrice()).isEqualTo(120_000);
        long storedBefore = jdbc.sql("SELECT total_price FROM bookings WHERE id = :id")
                .param("id", before.getId()).query(Long.class).single();
        assertThat(storedBefore).isEqualTo(100_000);
    }

    @Test
    void failedCourtUpdateLeavesItUnchanged() throws Exception {
        long courtId = ownedCourt();

        save(put("/api/owner/courts/{id}", courtId), court("Renamed", "[\"football\"]", 60,
                "[{\"startMinute\": 360, \"endMinute\": 1000, \"rate\": 120000}]"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT count(*) FROM price_rules WHERE court_id = :id").param("id", courtId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT name FROM courts WHERE id = :id").param("id", courtId)
                .query(String.class).single()).isEqualTo("Court 1");
    }

    @Test
    void ownersCannotTouchAnotherOwnersVenueOrCourts() throws Exception {
        long others = createCourt();

        save(put("/api/owner/venues/{id}", venueOf(others)), venue(360, 1560)).andExpect(status().isNotFound());
        save(put("/api/owner/courts/{id}", others), court("Mine", "[\"football\"]", 60, STANDARD_RULES))
                .andExpect(status().isNotFound());
        addCourt(venueOf(others), court("Mine", "[\"football\"]", 60, STANDARD_RULES)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM courts").query(Long.class).single()).isEqualTo(1);
    }

    // --- helpers ---

    /** A court (with the standard rules) at a venue that belongs to {@link #owner}. */
    private long ownedCourt() {
        long courtId = createCourt();
        jdbc.sql("UPDATE venues SET owner_id = :owner WHERE id = :id").param("owner", owner).param("id", venueOf(courtId))
                .update();
        addStandardPriceRules(courtId);
        return courtId;
    }

    private static String venue(int open, int close) {
        return """
                {"name": "Green Arena", "area": "Cidco", "address": "N-5, Aurangabad", "openMinute": %d,
                 "closeMinute": %d, "refundsPerMonth": 1}
                """.formatted(open, close);
    }

    private static String court(String name, String sports, int slotMinutes, String rules) {
        return """
                {"name": "%s", "sports": %s, "slotMinutes": %d, "advanceAmount": 20000,
                 "refundPolicy": "Full refund 24h ahead", "priceRules": %s}
                """.formatted(name, sports, slotMinutes, rules);
    }

    private ResultActions create(int open, int close, String... courts) throws Exception {
        return save(post("/api/owner/venues"),
                "{\"venue\": %s, \"courts\": [%s]}".formatted(venue(open, close), String.join(",", courts)));
    }

    private ResultActions addCourt(long venueId, String court) throws Exception {
        return save(post("/api/owner/venues/{id}/courts", venueId), court);
    }

    private ResultActions save(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long venueCount() {
        return jdbc.sql("SELECT count(*) FROM venues WHERE owner_id = :owner").param("owner", owner)
                .query(Long.class).single();
    }
}
