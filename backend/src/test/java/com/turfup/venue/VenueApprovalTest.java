package com.turfup.venue;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.BookingSource;
import com.turfup.booking.BookingStatus;
import com.turfup.demo.DemoDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unapproved venues are hidden from players (every court) but usable by their owner; only admins approve. */
class VenueApprovalTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService bookingService;

    @Autowired
    VenueApprovalService approvals;

    @Autowired
    VenueSetupService venueSetup;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    org.springframework.context.ApplicationContext context;

    long courtId;
    long venueId;
    long owner;
    long admin;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        venueId = venueOf(courtId);
        addStandardPriceRules(courtId);
        jdbc.sql("UPDATE venues SET approved = false WHERE id = :id").param("id", venueId).update();
        owner = ownerOf(courtId);
        admin = createUser("9999999998", "ADMIN");
    }

    @Test
    void unapprovedVenueIsHiddenFromPlayersOnEveryCourt() throws Exception {
        long court2 = addCourt(venueId, "Court 2", 60, "football");
        addStandardPriceRules(court2);

        for (long court : new long[] {courtId, court2}) {
            mvc.perform(get("/api/courts/{id}/slots", court).param("date", "2030-01-15"))
                    .andExpect(status().isNotFound());
            assertThatThrownBy(() -> bookingService.hold(court, createPlayer((int) court), START, START.plusHours(1)))
                    .isInstanceOf(CourtNotFoundException.class);
        }
        mvc.perform(get("/api/venues/{id}", venueId)).andExpect(status().isNotFound());
    }

    @Test
    void ownerCanStillSetUpAndEnterWalkInsOnUnapprovedVenue() throws Exception {
        mvc.perform(put("/api/owner/courts/{id}", courtId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Renamed", "sports": ["football"], "slotMinutes": 60, "advanceAmount": 20000,
                                 "priceRules": [{"startMinute": 360, "endMinute": 1560, "rate": 90000}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.courts[0].name").value("Renamed"));

        var walkIn = bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(START, START.plusHours(1), BookingSource.WALKIN, null, "Sameer", null));
        assertThat(walkIn.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(walkIn.getTotalPrice()).isEqualTo(90_000);
    }

    @Test
    void adminApprovesAndUnapproves() throws Exception {
        approval(admin, Role.ADMIN, venueId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));
        mvc.perform(get("/api/courts/{id}/slots", courtId).param("date", "2030-01-15"))
                .andExpect(status().isOk());
        assertThat(bookingService.hold(courtId, createPlayer(1), START, START.plusHours(1)).getStatus())
                .isEqualTo(BookingStatus.HELD);

        approval(admin, Role.ADMIN, venueId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));
        mvc.perform(get("/api/courts/{id}/slots", courtId).param("date", "2030-01-15"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminSeesTheReviewQueue() throws Exception {
        long approvedVenue = venueOf(createCourt());

        mvc.perform(get("/api/admin/venues").header(HttpHeaders.AUTHORIZATION, bearer(admin, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].venue.id").value(venueId))
                .andExpect(jsonPath("$[0].venue.courts[0].id").value(courtId))
                .andExpect(jsonPath("$[0].ownerPhone").value("8000000001"));
        mvc.perform(get("/api/admin/venues").param("approved", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin, Role.ADMIN)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].venue.id").value(approvedVenue));
    }

    @Test
    void onlyAdminsCanApprove() throws Exception {
        approval(owner, Role.OWNER, venueId, true).andExpect(status().isForbidden());
        approval(createPlayer(1), Role.PLAYER, venueId, true).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/venues").header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isForbidden());

        assertThat(jdbc.sql("SELECT approved FROM venues WHERE id = :id").param("id", venueId)
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void approvingUnknownVenueIs404() throws Exception {
        approval(admin, Role.ADMIN, 999_999, true).andExpect(status().isNotFound());
    }

    @Test
    void demoSeedCreatesLoginsAndAnApprovedMultiSportVenueOnce() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(jdbc, passwords, venueSetup, approvals, context);

        seeder.seed();
        seeder.seed();

        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE phone IN ('9999999999', '8888888888')")
                .query(Long.class).single()).isEqualTo(2);
        long demoVenue = jdbc.sql("""
                        SELECT v.id FROM venues v JOIN users u ON u.id = v.owner_id
                        WHERE u.phone = '8888888888' AND v.approved
                        """)
                .query(Long.class).single();
        login(DemoDataSeeder.ADMIN_PHONE, DemoDataSeeder.ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        login(DemoDataSeeder.OWNER_PHONE, DemoDataSeeder.OWNER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        String body = mvc.perform(get("/api/venues/{id}", demoVenue))
                .andExpect(jsonPath("$.name").value("Kick Arena"))
                .andExpect(jsonPath("$.courts.length()").value(3))
                .andExpect(jsonPath("$.courts[0].name").value("Main turf"))
                .andExpect(jsonPath("$.courts[0].advanceAmount").value(20000))
                .andExpect(jsonPath("$.sports[0].sport").value("football"))
                .andExpect(jsonPath("$.sports[2].sport").value("badminton"))
                .andExpect(jsonPath("$.sports[2].anyCourt").value(true))
                .andReturn().getResponse().getContentAsString();
        long mainTurf = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.courts[0].id")).longValue();
        mvc.perform(get("/api/courts/{id}/slots", mainTurf).param("date", "2030-01-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[10].price").value(80000))   // 4 PM
                .andExpect(jsonPath("$.slots[11].price").value(100000)); // 5 PM

        // Two more approved venues, each with its own owner and two courts.
        assertThat(jdbc.sql("""
                        SELECT v.name || ' ' || v.area || ' ' || u.phone || ' ' || count(c.id)
                        FROM venues v JOIN users u ON u.id = v.owner_id JOIN courts c ON c.venue_id = v.id
                        WHERE v.approved AND v.name <> 'Kick Arena'
                        GROUP BY v.id, u.phone ORDER BY v.name
                        """).query(String.class).list())
                .containsExactly("Street Sports Arena Garkheda 8888888881 2", "The Pavilion Arena Altamash Colony 8888888882 2");
        login(DemoDataSeeder.STREET_OWNER_PHONE, DemoDataSeeder.OWNER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(get("/api/venues").param("sport", "pickleball"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Street Sports Arena"));
        mvc.perform(get("/api/venues").param("sport", "tennis"))
                .andExpect(jsonPath("$[0].name").value("The Pavilion Arena"))
                .andExpect(jsonPath("$[0].fromRate").value(40000));
    }

    private ResultActions approval(long userId, Role role, long venue, boolean approved) throws Exception {
        return mvc.perform(put("/api/admin/venues/{id}/approval", venue)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\": %s}".formatted(approved)));
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(phone, password)));
    }
}
