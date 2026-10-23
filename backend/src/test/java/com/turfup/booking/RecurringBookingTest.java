package com.turfup.booking;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import com.turfup.auth.PlayerNotFoundException;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.CancellationService.RefundOutcome;
import com.turfup.booking.RecurringBookingService.RecurringRequest;
import com.turfup.booking.RecurringBookingService.Saved;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.venue.CourtHours;
import com.turfup.venue.CourtNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standing slots. Turf: 6 AM to 2 AM, 60-minute slots, ₹1,000/hr in the evening. The standing slot is 8-9 PM on
 * the weekday two days from today, so the four-week window always holds exactly four occurrences:
 * today+2, +9, +16, +23. Dates are taken from the database clock.
 */
class RecurringBookingTest extends PostgresTestBase {

    private static final int EIGHT_PM = 1200;
    private static final int NINE_PM = 1260;

    @Autowired
    RecurringBookingService recurring;

    @Autowired
    RecurringBookingJob job;

    @Autowired
    BookingService bookingService;

    @Autowired
    CancellationService cancellations;

    @Autowired
    PaymentService payments;

    @Autowired
    CheckoutService checkout;

    @Autowired
    OwnerScheduleService schedules;

    @Autowired
    MyBookingsService myBookings;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    MockMvc mvc;

    long courtId;
    long owner;
    long rahul;
    LocalDate today;
    int dow;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        owner = ownerOf(courtId);
        rahul = createPlayer(1);
        OffsetDateTime now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
        today = new CourtHours(360, 1560, 60).currentOperatingDate(now);
        dow = today.plusDays(2).getDayOfWeek().getValue();
    }

    @Test
    void createsFourWeeksOfConfirmedPhoneBookingsWithNoAdvance() {
        Saved saved = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM));

        assertThat(saved.generated().created()).isEqualTo(4);
        assertThat(saved.generated().skipped()).isZero();
        assertThat(saved.recurring().upcoming()).isEqualTo(4);
        assertThat(saved.recurring().playerName()).isEqualTo("PLAYER 9000000001");

        List<Booking> rows = occurrences(saved.recurring().id());
        assertThat(rows).extracting(b -> b.getStartAt().toInstant())
                .containsExactly(at(2, EIGHT_PM), at(9, EIGHT_PM), at(16, EIGHT_PM), at(23, EIGHT_PM));
        assertThat(rows).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(b.getSource()).isEqualTo(BookingSource.PHONE);
            assertThat(b.getPlayerId()).isEqualTo(rahul);
            assertThat(b.getAdvanceRequired()).isZero();
            assertThat(b.getTotalPrice()).isEqualTo(100_000);
        });
    }

    @Test
    void generationIsIdempotent() {
        long id = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM)).recurring().id();

        assertThat(job.generateAll()).isZero();
        assertThat(occurrences(id)).hasSize(4);
    }

    @Test
    void jobGeneratesForStandingSlotsThatHaveNoBookingsYet() {
        long id = jdbc.sql("""
                        INSERT INTO recurring_bookings (court_id, player_id, day_of_week, start_minute, end_minute, valid_from)
                        VALUES (:courtId, :player, :dow, 1200, 1260, :from) RETURNING id
                        """)
                .param("courtId", courtId).param("player", rahul).param("dow", dow).param("from", today)
                .query(Long.class).single();

        assertThat(job.generateAll()).isEqualTo(4);
        assertThat(occurrences(id)).hasSize(4);
    }

    @Test
    void respectsValidityDates() {
        Saved saved = recurring.create(owner, courtId, new RecurringRequest(rahul, dow, EIGHT_PM, NINE_PM,
                today.plusDays(5), today.plusDays(17), null));

        assertThat(occurrences(saved.recurring().id())).extracting(b -> b.getStartAt().toInstant())
                .containsExactly(at(9, EIGHT_PM), at(16, EIGHT_PM));
    }

    @Test
    void takenSlotIsSkippedAndFlaggedThenBookedOnceFreed() throws Exception {
        long walkIn = bookingService.enterForOwner(owner, courtId, new OwnerEntry(
                atTime(9, EIGHT_PM), atTime(9, NINE_PM), BookingSource.WALKIN, null, "Sameer", null)).getId();

        Saved saved = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM));

        assertThat(saved.generated().created()).isEqualTo(3);
        assertThat(saved.generated().skipped()).isEqualTo(1);
        var skips = schedules.schedule(owner, venueOf(courtId), today.plusDays(9)).recurringSkips();
        assertThat(skips).singleElement().satisfies(skip -> {
            assertThat(skip.reason()).isEqualTo("SLOT_TAKEN");
            assertThat(skip.playerId()).isEqualTo(rahul);
            assertThat(skip.startAt().toInstant()).isEqualTo(at(9, EIGHT_PM));
        });
        mvc.perform(get("/api/owner/venues/{id}/schedule", venueOf(courtId)).param("date", today.plusDays(9).toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(jsonPath("$.recurringSkips[0].reason").value("SLOT_TAKEN"))
                .andExpect(jsonPath("$.recurringSkips[0].playerName").value("PLAYER 9000000001"));

        cancellations.cancelByOwner(owner, walkIn, CancelReason.OTHER, "Moved to another day");
        assertThat(job.generateAll()).isEqualTo(1);

        assertThat(occurrences(saved.recurring().id())).hasSize(4);
        assertThat(schedules.schedule(owner, venueOf(courtId), today.plusDays(9)).recurringSkips()).isEmpty();
    }

    @Test
    void activeHoldBlocksButExpiredHoldDoesNot() {
        long held = bookingService.hold(courtId, createPlayer(2), atTime(2, EIGHT_PM), atTime(2, NINE_PM)).getId();
        bookingService.hold(courtId, createPlayer(3), atTime(9, EIGHT_PM), atTime(9, NINE_PM));
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 second' WHERE id = :id").param("id", held).update();

        Saved saved = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM));

        assertThat(saved.generated().created()).isEqualTo(3); // +2 (expired hold cleared), +16, +23
        assertThat(saved.generated().skipped()).isEqualTo(1); // +9 is still being paid for
        assertThat(statusOf(held)).isEqualTo("EXPIRED");
    }

    @Test
    void slotsThatNoLongerFitTheTurfAreSkippedWithAReason() {
        long id = recurring.create(owner, courtId, new RecurringRequest(rahul, dow, NINE_PM, NINE_PM + 60, null, null, null))
                .recurring().id();
        jdbc.sql("DELETE FROM bookings WHERE recurring_id = :id").param("id", id).update();

        jdbc.sql("UPDATE venues SET close_minute = 1260 WHERE id = (SELECT venue_id FROM courts WHERE id = :id)").param("id", courtId).update(); // now closes 9 PM
        job.generateAll();
        assertThat(skipReasons(id)).containsOnly("OUTSIDE_HOURS").hasSize(4);

        jdbc.sql("UPDATE venues SET close_minute = 1560 WHERE id = (SELECT venue_id FROM courts WHERE id = :id)").param("id", courtId).update();
        jdbc.sql("DELETE FROM price_rules WHERE court_id = :id AND start_minute = 1020").param("id", courtId).update();
        addPriceRule(courtId, null, 1020, 1260, 100_000); // nothing priced after 9 PM
        job.generateAll();
        assertThat(skipReasons(id)).containsOnly("NO_PRICE").hasSize(4);
        assertThat(occurrences(id)).isEmpty();
    }

    @Test
    void endingCancelsFutureOccurrencesWithoutCountingRefundsAndStopsGeneration() {
        Saved saved = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM));
        long id = saved.recurring().id();
        long firstOccurrence = occurrences(id).getFirst().getId();
        payments.recordBalanceReceived(owner, firstOccurrence, PaymentMethod.UPI); // paid ahead at the counter
        long past = jdbc.sql("""
                        INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price,
                                              advance_required, recurring_id)
                        VALUES (:courtId, :player, now() - interval '7 days', now() - interval '7 days' + interval '1 hour',
                                'CONFIRMED', 'PHONE', 100000, 0, :id) RETURNING id
                        """)
                .param("courtId", courtId).param("player", rahul).param("id", id).query(Long.class).single();

        var ended = recurring.end(owner, id);

        assertThat(ended.cancelled()).isEqualTo(4);
        assertThat(occurrences(id)).filteredOn(b -> b.getId() != past).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(b.getCancelledBy()).isEqualTo("OWNER");
            assertThat(b.getCancelReason()).isEqualTo("RECURRING_CHANGED");
        });
        assertThat(statusOf(past)).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT amount || ' ' || method || ' ' || refund_reason FROM payments WHERE kind = 'REFUND'")
                .query(String.class).list()).containsExactly("100000 UPI OWNER_CANCELLATION");
        assertThat(job.generateAll()).isZero();
        assertThat(recurring.list(owner, venueOf(courtId))).isEmpty();

        // The player's monthly refund allowance is untouched.
        long online = bookingService.hold(courtId, rahul, atTime(4, EIGHT_PM), atTime(4, NINE_PM)).getId();
        checkout.payAdvance(online, rahul);
        assertThat(cancellations.quote(online, rahul).outcome()).isEqualTo(RefundOutcome.REFUND);

        // And the player sees why.
        assertThat(myBookings.list(rahul)).filteredOn(b -> "RECURRING_CHANGED".equals(b.cancelReason())).hasSize(4);
    }

    @Test
    void editingEndsTheOldPatternAndCreatesTheNewOne() {
        long oldId = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM)).recurring().id();

        Saved edited = recurring.update(owner, oldId, new RecurringRequest(null, dow, NINE_PM, NINE_PM + 60, null, null, null));

        assertThat(edited.recurring().id()).isNotEqualTo(oldId);
        assertThat(edited.recurring().playerId()).isEqualTo(rahul);
        assertThat(edited.generated().created()).isEqualTo(4);
        assertThat(occurrences(oldId)).allSatisfy(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
        assertThat(occurrences(edited.recurring().id())).extracting(b -> b.getStartAt().toInstant())
                .containsExactly(at(2, NINE_PM), at(9, NINE_PM), at(16, NINE_PM), at(23, NINE_PM));
        assertThat(recurring.list(owner, venueOf(courtId))).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(edited.recurring().id()));
    }

    @Test
    void editingToTheSameTimeWorksDespiteTheCancelledOccurrences() {
        long oldId = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM)).recurring().id();

        Saved edited = recurring.update(owner, oldId, pattern(EIGHT_PM, NINE_PM));

        assertThat(edited.generated().created()).isEqualTo(4);
    }

    @Test
    void occurrencesCancelledIndividuallyAreNeverRecreated() {
        long id = recurring.create(owner, courtId, pattern(EIGHT_PM, NINE_PM)).recurring().id();
        List<Booking> rows = occurrences(id);
        cancellations.cancelByPlayer(rows.get(0).getId(), rahul, null);
        cancellations.cancelByOwner(owner, rows.get(1).getId(), CancelReason.RAIN, null);

        assertThat(job.generateAll()).isZero();
        assertThat(occurrences(id)).hasSize(4);
    }

    @Test
    void invalidPatternsAreRejected() {
        assertThatThrownBy(() -> recurring.create(owner, courtId, pattern(1230, 1290)))
                .isInstanceOf(InvalidRecurringException.class).hasMessageContaining("court's slots");
        assertThatThrownBy(() -> recurring.create(owner, courtId, pattern(300, 360)))
                .isInstanceOf(InvalidRecurringException.class).hasMessageContaining("opening hours");
        assertThatThrownBy(() -> recurring.create(owner, courtId, new RecurringRequest(rahul, 8, EIGHT_PM, NINE_PM, null, null, null)))
                .isInstanceOf(InvalidRecurringException.class);
        assertThatThrownBy(() -> recurring.create(owner, courtId,
                new RecurringRequest(rahul, dow, EIGHT_PM, NINE_PM, today.plusDays(5), today, null)))
                .isInstanceOf(InvalidRecurringException.class);
        assertThatThrownBy(() -> recurring.create(owner, courtId, new RecurringRequest(999_999L, dow, EIGHT_PM, NINE_PM, null, null, null)))
                .isInstanceOf(PlayerNotFoundException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM recurring_bookings").query(Long.class).single()).isZero();
    }

    @Test
    void ownersOnlyManageTheirOwnStandingSlots() {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        long othersId = recurring.create(ownerOf(otherTurf), otherTurf, pattern(EIGHT_PM, NINE_PM)).recurring().id();

        assertThatThrownBy(() -> recurring.create(owner, otherTurf, pattern(EIGHT_PM, NINE_PM)))
                .isInstanceOf(CourtNotFoundException.class);
        assertThatThrownBy(() -> recurring.end(owner, othersId)).isInstanceOf(RecurringNotFoundException.class);
        assertThatThrownBy(() -> recurring.update(owner, othersId, pattern(NINE_PM, NINE_PM + 60)))
                .isInstanceOf(RecurringNotFoundException.class);
        assertThat(occurrences(othersId)).allSatisfy(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED));
    }

    @Test
    void overHttp() throws Exception {
        String auth = bearer(owner, Role.OWNER);
        String body = mvc.perform(post("/api/owner/courts/{id}/recurring", courtId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerId": %d, "dayOfWeek": %d, "startMinute": 1200, "endMinute": 1260}
                                """.formatted(rahul, dow)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generated.created").value(4))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.recurring.id")).longValue();

        mvc.perform(get("/api/owner/venues/{id}/recurring", venueOf(courtId)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$[0].upcoming").value(4))
                .andExpect(jsonPath("$[0].validFrom").value(today.toString()));
        mvc.perform(get("/api/owner/venues/{id}/schedule", venueOf(courtId)).param("date", today.plusDays(2).toString())
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.bookings[0].recurringId").value(id))
                .andExpect(jsonPath("$.bookings[0].source").value("PHONE"));
        mvc.perform(post("/api/owner/courts/{id}/recurring", courtId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": %d, \"dayOfWeek\": %d, \"startMinute\": 1230, \"endMinute\": 1290}".formatted(rahul, dow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECURRING"));
        mvc.perform(post("/api/owner/recurring/{id}/end", id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(4));
        mvc.perform(post("/api/owner/recurring/{id}/end", id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private RecurringRequest pattern(int startMinute, int endMinute) {
        return new RecurringRequest(rahul, dow, startMinute, endMinute, null, null, null);
    }

    private OffsetDateTime atTime(int daysAhead, int minute) {
        return today.plusDays(daysAhead).atStartOfDay(CourtHours.IST).plusMinutes(minute).toOffsetDateTime();
    }

    private Instant at(int daysAhead, int minute) {
        return atTime(daysAhead, minute).toInstant();
    }

    private List<Booking> occurrences(long recurringId) {
        List<Long> ids = jdbc.sql("SELECT id FROM bookings WHERE recurring_id = :id ORDER BY start_at")
                .param("id", recurringId).query(Long.class).list();
        return ids.stream().map(i -> bookingRepository.findById(i).orElseThrow()).toList();
    }

    private List<String> skipReasons(long recurringId) {
        return jdbc.sql("SELECT reason FROM recurring_skips WHERE recurring_id = :id").param("id", recurringId)
                .query(String.class).list();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }
}
