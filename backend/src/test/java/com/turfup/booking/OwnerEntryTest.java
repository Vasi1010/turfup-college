package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.auth.PlayerNotFoundException;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.venue.InvalidSlotException;
import com.turfup.venue.CourtHours;
import com.turfup.venue.CourtNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Owner walk-in and phone entry. Turf: 6 AM to 2 AM, 60-minute slots, standard tariff. */
class OwnerEntryTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired
    BookingService bookingService;

    @Autowired
    MockMvc mvc;

    long courtId;
    long owner;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        owner = ownerOf(courtId);
    }

    @Test
    void walkInIsConfirmedImmediatelyWithComputedPriceAndNoAdvance() throws Exception {
        mvc.perform(post("/api/owner/courts/{id}/bookings", courtId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startAt": "2030-01-15T20:00:00+05:30", "endAt": "2030-01-15T21:00:00+05:30",
                                 "source": "WALKIN", "walkinName": "Sameer", "walkinPhone": "9822000000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.source").value("WALKIN"))
                .andExpect(jsonPath("$.walkinName").value("Sameer"))
                .andExpect(jsonPath("$.playerId").isEmpty())
                .andExpect(jsonPath("$.totalPrice").value(100000))
                .andExpect(jsonPath("$.advanceRequired").value(0))
                .andExpect(jsonPath("$.holdExpiresAt").isEmpty());
    }

    @Test
    void phoneBookingForARegisteredPlayer() {
        long player = createPlayer(1);

        Booking b = bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(START, END, BookingSource.PHONE, player, null, null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getSource()).isEqualTo(BookingSource.PHONE);
        assertThat(b.getPlayerId()).isEqualTo(player);
    }

    @Test
    void blankNameIsSavedAsWalkInSoEntryTakesTwoTaps() throws Exception {
        mvc.perform(post("/api/owner/courts/{id}/bookings", courtId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startAt": "2030-01-15T20:00:00+05:30", "endAt": "2030-01-15T21:00:00+05:30",
                                 "source": "WALKIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walkinName").value("Walk-in"));

        Booking phone = bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(END, END.plusHours(1), BookingSource.PHONE, null, "  ", "9822000000"));
        assertThat(phone.getWalkinName()).isEqualTo("Phone booking");
        assertThat(phone.getWalkinPhone()).isEqualTo("9822000000");
    }

    @Test
    void ownerCannotEnterOnlineBookings() {
        assertThatThrownBy(() -> bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(START, END, BookingSource.ONLINE, null, "Sameer", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownPlayerIsRejected() {
        assertThatThrownBy(() -> bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(START, END, BookingSource.PHONE, 999_999L, null, null)))
                .isInstanceOf(PlayerNotFoundException.class);
    }

    @Test
    void sameSlotChecksAsOnlineBookings() {
        assertThatThrownBy(() -> walkIn(START.plusMinutes(30), END.plusMinutes(30)))
                .isInstanceOfSatisfying(InvalidSlotException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidSlotException.Reason.MISALIGNED));
        assertThatThrownBy(() -> walkIn(START.minusYears(10), END.minusYears(10)))
                .isInstanceOfSatisfying(InvalidSlotException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidSlotException.Reason.EARLIER_DAY));
        assertThatThrownBy(() -> walkIn(START.withHour(3), END.withHour(4)))
                .isInstanceOfSatisfying(InvalidSlotException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidSlotException.Reason.OUTSIDE_HOURS));
    }

    @Test
    void slotHeldByAPlayerIsUnavailable() {
        bookingService.hold(courtId, createPlayer(1), START, END);

        assertThatThrownBy(() -> walkIn(START, END)).isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void expiredHoldDoesNotBlockWalkIn() {
        Booking held = bookingService.hold(courtId, createPlayer(1), START, END);
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", held.getId()).update();

        assertThat(walkIn(START, END).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", held.getId())
                .query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void ownerCanEnterBookingsWhileListingIsInactive() {
        jdbc.sql("UPDATE venues SET active = false WHERE id = (SELECT venue_id FROM courts WHERE id = :id)").param("id", courtId).update();

        assertThat(walkIn(START, END).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> bookingService.hold(courtId, createPlayer(1), END, END.plusHours(1)))
                .isInstanceOf(CourtNotFoundException.class);
    }

    // --- today's slots that already started or finished (24-hour turf, so "now" is always inside hours) ---

    @Test
    void ownerCanEnterTheSlotInProgressButAPlayerCannotHoldIt() {
        long allDay = allDayTurf();
        OffsetDateTime slotStart = currentHourStart();

        Booking b = bookingService.enterForOwner(ownerOf(allDay), allDay,
                new OwnerEntry(slotStart, slotStart.plusHours(1), BookingSource.WALKIN, null, "Late arrival", null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getTotalPrice()).isEqualTo(100_000);
        assertThatThrownBy(() -> bookingService.hold(allDay, createPlayer(1), slotStart, slotStart.plusHours(1)))
                .isInstanceOfSatisfying(InvalidSlotException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidSlotException.Reason.PAST));
    }

    @Test
    void ownerCanLogAFinishedSlotFromEarlierToday() {
        // A 24-hour turf that opened on the hour 90-149 minutes before the database's now(). Its first slot ended
        // at least 30 minutes ago and belongs to the current operating day, whatever the time of day (even just
        // after midnight, when the operating day began yesterday).
        OffsetDateTime openedAt = dbNow().minusMinutes(90).atZoneSameInstant(CourtHours.IST)
                .truncatedTo(ChronoUnit.HOURS).toOffsetDateTime();
        int openMinute = openedAt.atZoneSameInstant(CourtHours.IST).getHour() * 60;
        long turf = createCourt(openMinute, openMinute + 1440, 60);
        addPriceRule(turf, null, 0, 2880, 100_000);

        Booking b = bookingService.enterForOwner(ownerOf(turf), turf,
                new OwnerEntry(openedAt, openedAt.plusHours(1), BookingSource.PHONE, null, "Logged later", null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getEndAt()).isBefore(dbNow());
    }

    @Test
    void ownerCannotEnterASlotOnAnEarlierOperatingDay() {
        long allDay = allDayTurf();
        OffsetDateTime yesterday = currentHourStart().minusDays(1);

        assertThatThrownBy(() -> bookingService.enterForOwner(ownerOf(allDay), allDay,
                new OwnerEntry(yesterday, yesterday.plusHours(1), BookingSource.WALKIN, null, "Too late", null)))
                .isInstanceOfSatisfying(InvalidSlotException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidSlotException.Reason.EARLIER_DAY));
    }

    @Test
    void enteringAPastSlotStillCannotDoubleBook() {
        long allDay = allDayTurf();
        OffsetDateTime slotStart = currentHourStart();
        long owner = ownerOf(allDay);
        bookingService.enterForOwner(owner, allDay,
                new OwnerEntry(slotStart, slotStart.plusHours(1), BookingSource.WALKIN, null, "First", null));

        assertThatThrownBy(() -> bookingService.enterForOwner(owner, allDay,
                new OwnerEntry(slotStart, slotStart.plusHours(1), BookingSource.WALKIN, null, "Second", null)))
                .isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void walkInAndOnlineHoldRacingForTheSameSlotYieldExactlyOneBooking() throws Exception {
        int rounds = 20;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < rounds; round++) {
                OffsetDateTime start = START.plusDays(round);
                OffsetDateTime end = start.plusHours(1);
                long player = createPlayer(round);
                CountDownLatch go = new CountDownLatch(1);

                Future<Boolean> online = pool.submit(attempt(go, () -> bookingService.hold(courtId, player, start, end)));
                Future<Boolean> walkIn = pool.submit(attempt(go, () -> walkIn(start, end)));
                go.countDown();

                boolean onlineWon = online.get(30, TimeUnit.SECONDS);
                boolean walkInWon = walkIn.get(30, TimeUnit.SECONDS);
                assertThat(onlineWon ^ walkInWon).as("exactly one wins in round %d", round).isTrue();
                long active = jdbc.sql("""
                                SELECT count(*) FROM bookings
                                WHERE court_id = :courtId AND start_at = :start AND status IN ('HELD', 'CONFIRMED')
                                """)
                        .param("courtId", courtId)
                        .param("start", start)
                        .query(Long.class)
                        .single();
                assertThat(active).isEqualTo(1);
            }
        }
    }

    /** True if the booking was made, false if the slot was taken; any other exception fails the test. */
    private static Callable<Boolean> attempt(CountDownLatch go, Runnable booking) {
        return () -> {
            go.await();
            try {
                booking.run();
                return true;
            } catch (SlotUnavailableException e) {
                return false;
            }
        };
    }

    private long allDayTurf() {
        long id = createCourt(0, 1440, 60);
        addPriceRule(id, null, 0, 2880, 100_000);
        return id;
    }

    private OffsetDateTime dbNow() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
    }

    /** Start of the IST hour containing the database's now(). */
    private OffsetDateTime currentHourStart() {
        OffsetDateTime now = dbNow();
        return now.atZoneSameInstant(CourtHours.IST)
                .truncatedTo(ChronoUnit.HOURS)
                .toOffsetDateTime();
    }

    private Booking walkIn(OffsetDateTime start, OffsetDateTime end) {
        return bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(start, end, BookingSource.WALKIN, null, "Sameer", null));
    }
}
