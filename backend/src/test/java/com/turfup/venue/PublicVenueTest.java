package com.turfup.venue;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public browse endpoints, no login. Only active, approved venues and their active courts are listed. */
class PublicVenueTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    long cidco;       // court: football, with two badminton courts at the same venue
    long badminton1;
    long badminton2;
    long osmanpura;   // court: football only
    long hidden;

    @BeforeEach
    void setUp() {
        cidco = createCourt();
        addStandardPriceRules(cidco);
        badminton1 = addCourt(venueOf(cidco), "Badminton 1", 60, "badminton");
        addPriceRule(badminton1, null, 0, 2880, 40_000);
        badminton2 = addCourt(venueOf(cidco), "Badminton 2", 60, "badminton");
        addPriceRule(badminton2, null, 0, 2880, 50_000);
        jdbc.sql("UPDATE courts SET advance_amount = 10000 WHERE id IN (:a, :b)")
                .param("a", badminton1).param("b", badminton2).update();

        osmanpura = createCourt();
        addPriceRule(osmanpura, null, 0, 2880, 90_000);
        jdbc.sql("UPDATE venues SET name = 'Goal Box', area = 'Osmanpura' WHERE id = :id")
                .param("id", venueOf(osmanpura)).update();

        hidden = createCourt(); // unapproved
        jdbc.sql("UPDATE venues SET approved = false WHERE id = :id").param("id", venueOf(hidden)).update();
        long inactive = createCourt();
        jdbc.sql("UPDATE venues SET active = false WHERE id = :id").param("id", venueOf(inactive)).update();
    }

    @Test
    void listsOnlyPublicVenuesWithSportsAndLowestPrices() throws Exception {
        mvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(venueOf(cidco)))
                .andExpect(jsonPath("$[0].fromRate").value(40000))
                .andExpect(jsonPath("$[0].advanceFrom").value(10000))
                .andExpect(jsonPath("$[0].sports[0].sport").value("football"))
                .andExpect(jsonPath("$[0].sports[0].courts").value(1))
                .andExpect(jsonPath("$[0].sports[1].sport").value("badminton"))
                .andExpect(jsonPath("$[0].sports[1].courts").value(2))
                .andExpect(jsonPath("$[1].name").value("Goal Box"))
                .andExpect(jsonPath("$[1].sports.length()").value(1));
    }

    @Test
    void filtersBySportAndPricesOnlyThatSportsCourts() throws Exception {
        mvc.perform(get("/api/venues").param("sport", "football"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromRate").value(80000))
                .andExpect(jsonPath("$[0].advanceFrom").value(20000));
        mvc.perform(get("/api/venues").param("sport", "badminton"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(venueOf(cidco)))
                .andExpect(jsonPath("$[0].fromRate").value(40000));
        mvc.perform(get("/api/venues").param("sport", "tennis")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void filtersByAreaIgnoringCase() throws Exception {
        mvc.perform(get("/api/venues").param("area", "osmanpura"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(venueOf(osmanpura)));
    }

    @Test
    void areasAndSportsCountOnlyPublicVenues() throws Exception {
        mvc.perform(get("/api/venues/areas"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].area").value("Cidco"))
                .andExpect(jsonPath("$[0].venues").value(1))
                .andExpect(jsonPath("$[1].area").value("Osmanpura"));
        mvc.perform(get("/api/venues/sports"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].sport").value("football"))
                .andExpect(jsonPath("$[0].venues").value(2))
                .andExpect(jsonPath("$[3].sport").value("badminton"))
                .andExpect(jsonPath("$[3].venues").value(1))
                .andExpect(jsonPath("$[4].venues").value(0));
    }

    @Test
    void detailListsCourtsAndOffersAnyCourtWhereSlotsLineUp() throws Exception {
        mvc.perform(get("/api/venues/{id}", venueOf(cidco)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openMinute").value(360))
                .andExpect(jsonPath("$.closeMinute").value(1560))
                .andExpect(jsonPath("$.refundsPerMonth").value(1))
                .andExpect(jsonPath("$.courts.length()").value(3))
                .andExpect(jsonPath("$.courts[0].id").value(cidco))
                .andExpect(jsonPath("$.courts[0].slotMinutes").value(60))
                .andExpect(jsonPath("$.courts[0].refundFullHoursBefore").value(24))
                .andExpect(jsonPath("$.courts[0].fromRate").value(80000))
                .andExpect(jsonPath("$.courts[1].name").value("Badminton 1"))
                .andExpect(jsonPath("$.sports[0].sport").value("football"))
                .andExpect(jsonPath("$.sports[0].anyCourt").value(false))
                .andExpect(jsonPath("$.sports[1].sport").value("badminton"))
                .andExpect(jsonPath("$.sports[1].courtIds.length()").value(2))
                .andExpect(jsonPath("$.sports[1].anyCourt").value(true));

        // Different slot lengths: no "any court".
        jdbc.sql("UPDATE courts SET slot_minutes = 30 WHERE id = :id").param("id", badminton2).update();
        mvc.perform(get("/api/venues/{id}", venueOf(cidco))).andExpect(jsonPath("$.sports[1].anyCourt").value(false));
    }

    @Test
    void pausedCourtsAreHidden() throws Exception {
        jdbc.sql("UPDATE courts SET active = false WHERE id = :id").param("id", badminton2).update();

        mvc.perform(get("/api/venues/{id}", venueOf(cidco)))
                .andExpect(jsonPath("$.courts.length()").value(2))
                .andExpect(jsonPath("$.sports[1].anyCourt").value(false));
        mvc.perform(get("/api/courts/{id}/slots", badminton2).param("date", "2030-01-15"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hiddenVenueDetailIs404() throws Exception {
        mvc.perform(get("/api/venues/{id}", venueOf(hidden))).andExpect(status().isNotFound());
    }
}
