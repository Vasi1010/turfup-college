package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.availability.AvailabilityService;
import com.turfup.availability.SlotStatus;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.CancellationService.RefundOutcome;
import com.turfup.booking.RecurringBookingService.RecurringRequest;
import com.turfup.payment.CheckoutService;
import com.turfup.venue.CourtNotFoundException;
import com.turfup.venue.VenueCustomerService;
import com.turfup.venue.VenueNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A venue with several courts: courts are booked independently, "any free court" spreads players over the
 * courts of a sport, and the venue-level rules (waivers, the monthly refund allowance, no-show counts) hold
 * across all its courts. Simulated payments, advance ₹200, 6 AM to 2 AM.
 */
class MultiCourtTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);
    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    CancellationService cancellations;

    @Autowired
    VenueCustomerService customers;

    @Autowired
    AvailabilityService availability;

    @Autowired
    RecurringBookingService recurring;

    long football;
    long venueId;
    long owner;
    long badminton1;
    long badminton2;
    long badminton3;
    long alice;

    @BeforeEach
    void setUp() {
        football = createCourt();
        addStandardPriceRules(football);
        venueId = venueOf(football);
        owner = ownerOf(football);
        badminton1 = addCourt(venueId, "Badminton 1", 60, "badminton");
        addPriceRule(badminton1, null, 0, 2880, 50_000);
        badminton2 = addCourt(venueId, "Badminton 2", 60, "badminton");
        addPriceRule(badminton2, null, 0, 2880, 40_000); // cheapest
        badminton3 = addCourt(venueId, "Badminton 3", 60, "badminton");
        addPriceRule(badminton3, null, 0, 2880, 50_000);
        alice = createPlayer(1);
    }

    // --- courts are independent ---

    @Test
    void twoCourtsOfOneVenueBookTheSameTimeIndependently() {
        Booking a = bookingService.hold(badminton1, alice, START, END);
        Booking b = bookingService.hold(badminton2, createPlayer(2), START, END);

        assertThat(a.getCourtId()).isEqualTo(badminton1);
        assertThat(b.getCourtId()).isEqualTo(badminton2);
        assertThat(b.getTotalPrice()).isEqualTo(40_000);
        assertThatThrownBy(() -> bookingService.hold(badminton1, createPlayer(3), START, END))
                .isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void anOwnerEntryOnOneCourtLeavesTheOthersFree() {
        bookingService.enterForOwner(owner, badminton1,
                new OwnerEntry(START, END, BookingSource.WALKIN, null, null, null));

        var slots = availability.slotsFor(badminton2, DAY).slots();
        assertThat(slots.stream().filter(s -> s.start().isEqual(START)).findFirst().orElseThrow().status())
                .isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void anOwnerCannotEnterBookingsOnAnotherOwnersCourt() {
        long others = createCourt();
        addStandardPriceRules(others);

        assertThatThrownBy(() -> bookingService.enterForOwner(owner, others,
                new OwnerEntry(START, END, BookingSource.WALKIN, null, null, null)))
                .isInstanceOf(CourtNotFoundException.class);
    }

    // --- any free court ---

    @Test
    void anyCourtTakesTheCheapestFreeCourtThenTheNextInDisplayOrder() {
        Booking first = bookingService.holdAnyCourt(venueId, "badminton", alice, START, END);
        Booking second = bookingService.holdAnyCourt(venueId, "Badminton", createPlayer(2), START, END);
        Booking third = bookingService.holdAnyCourt(venueId, "badminton", createPlayer(3), START, END);

        assertThat(first.getCourtId()).isEqualTo(badminton2);
        assertThat(first.getTotalPrice()).isEqualTo(40_000);
        assertThat(second.getCourtId()).isEqualTo(badminton1);
        assertThat(third.getCourtId()).isEqualTo(badminton3);
        assertThatThrownBy(() -> bookingService.holdAnyCourt(venueId, "badminton", createPlayer(4), START, END))
                .isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void anyCourtSlotsShowTheCheapestFreeCourtAndHowManyAreFree() {
        bookingService.hold(badminton2, alice, START, END);

        var any = availability.anyCourtSlots(venueId, "badminton", DAY);

        var eightPm = any.slots().stream().filter(s -> s.start().isEqual(START)).findFirst().orElseThrow();
        assertThat(eightPm.status()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(eightPm.freeCourts()).isEqualTo(2);
        assertThat(eightPm.price()).isEqualTo(50_000);
        var nine = any.slots().stream().filter(s -> s.start().isEqual(END)).findFirst().orElseThrow();
        assertThat(nine.freeCourts()).isEqualTo(3);
        assertThat(nine.price()).isEqualTo(40_000);

        bookingService.hold(badminton1, createPlayer(2), START, END);
        bookingService.hold(badminton3, createPlayer(3), START, END);
        var full = availability.anyCourtSlots(venueId, "badminton", DAY).slots().stream()
                .filter(s -> s.start().isEqual(START)).findFirst().orElseThrow();
        assertThat(full.status()).isEqualTo(SlotStatus.HELD);
        assertThat(full.freeCourts()).isZero();
    }

    @Test
    void anyCourtIsRefusedWhenTheCourtsSlotsDoNotLineUpOrNoCourtOffersTheSport() {
        jdbc.sql("UPDATE courts SET slot_minutes = 30 WHERE id = :id").param("id", badminton3).update();

        assertThatThrownBy(() -> bookingService.holdAnyCourt(venueId, "badminton", alice, START, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.anyCourtSlots(venueId, "badminton", DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bookingService.holdAnyCourt(venueId, "tennis", alice, START, END))
                .isInstanceOf(VenueNotFoundException.class);
    }

    @Test
    void fiftyConcurrentAnyCourtHoldsOnThreeCourts_exactlyThreeSucceedOnDifferentCourts() throws Exception {
        int requests = 50;
        List<Long> players = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            players.add(createPlayer(100 + i));
        }
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (long playerId : players) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return bookingService.holdAnyCourt(venueId, "badminton", playerId, START, END).getCourtId();
                    } catch (SlotUnavailableException e) {
                        return null;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Long> courts = new ArrayList<>();
            for (Future<Long> f : futures) {
                Long court = f.get(60, TimeUnit.SECONDS); // any other exception fails the test
                if (court != null) {
                    courts.add(court);
                }
            }
            assertThat(courts).containsExactlyInAnyOrder(badminton1, badminton2, badminton3);
        }
    }

    // --- venue-level rules across courts ---

    @Test
    void aWaiverAppliesOnEveryCourtOfTheVenue() {
        customers.setAdvanceWaived(owner, venueId, alice, true);

        assertThat(bookingService.hold(football, alice, START, END).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingService.hold(badminton1, alice, START, END).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        // Another venue's courts are not covered.
        long elsewhere = createCourt();
        addStandardPriceRules(elsewhere);
        assertThat(bookingService.hold(elsewhere, alice, END, END.plusHours(1)).getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void theMonthlyRefundAllowanceIsSharedByAllCourtsOfTheVenue() {
        long onFootball = confirmed(football, START);
        long onBadminton = confirmed(badminton1, START.plusDays(1));

        var first = cancellations.cancelByPlayer(onFootball, alice, null);
        var second = cancellations.cancelByPlayer(onBadminton, alice, null);

        assertThat(first.terms().outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThat(second.terms().outcome()).isEqualTo(RefundOutcome.NO_REFUND_LIMIT_REACHED);
    }

    @Test
    void theRefundNoticeIsTheCourts() {
        jdbc.sql("UPDATE courts SET refund_full_hours_before = 1000000 WHERE id = :id").param("id", badminton1).update();
        long onBadminton = confirmed(badminton1, START);
        long onFootball = confirmed(football, START.plusDays(1));

        assertThat(cancellations.quote(onBadminton, alice).outcome()).isEqualTo(RefundOutcome.NO_REFUND_TOO_LATE);
        assertThat(cancellations.quote(onFootball, alice).outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void aNoShowOnAnyCourtCountsAtTheVenue() {
        long a = confirmed(football, START);
        long b = confirmed(badminton1, START.plusDays(1));
        for (long id : new long[] {a, b}) {
            jdbc.sql("UPDATE bookings SET start_at = now() - interval '2 hours' - make_interval(days => id::int),"
                            + " end_at = now() - interval '1 hour' - make_interval(days => id::int) WHERE id = :id")
                    .param("id", id).update();
            bookingService.markNoShow(owner, id);
        }

        assertThat(customers.list(owner, venueId).customers()).singleElement()
                .satisfies(c -> assertThat(c.noShowCount()).isEqualTo(2));
    }

    // --- standing slots ---

    @Test
    void aStandingSlotCanMoveToAnotherCourtOfTheVenueButNotElsewhere() {
        LocalDate today = jdbc.sql("SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date").query(LocalDate.class).single();
        int dow = today.plusDays(3).getDayOfWeek().getValue();
        var saved = recurring.create(owner, badminton1, new RecurringRequest(alice, dow, 1200, 1260, null, null, null));
        assertThat(saved.recurring().courtId()).isEqualTo(badminton1);
        assertThat(saved.generated().created()).isEqualTo(4);

        var moved = recurring.update(owner, saved.recurring().id(),
                new RecurringRequest(null, dow, 1200, 1260, null, null, badminton2));

        assertThat(moved.recurring().courtId()).isEqualTo(badminton2);
        assertThat(moved.recurring().courtName()).isEqualTo("Badminton 2");
        assertThat(moved.generated().created()).isEqualTo(4);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM bookings WHERE court_id = :court AND status = 'CONFIRMED' AND start_at > now()
                        """).param("court", badminton1).query(Long.class).single()).isZero();
        assertThat(recurring.list(owner, venueId)).singleElement()
                .satisfies(r -> assertThat(r.courtId()).isEqualTo(badminton2));

        long elsewhere = createCourt();
        assertThatThrownBy(() -> recurring.update(owner, moved.recurring().id(),
                new RecurringRequest(null, dow, 1200, 1260, null, null, elsewhere)))
                .isInstanceOf(CourtNotFoundException.class);
    }

    private long confirmed(long court, OffsetDateTime start) {
        Booking held = bookingService.hold(court, alice, start, start.plusHours(1));
        assertThat(checkout.payAdvance(held.getId(), alice).booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        return held.getId();
    }
}
