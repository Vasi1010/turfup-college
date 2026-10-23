package com.turfup.demo;

import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.BookingSource;
import com.turfup.booking.BookingStatus;
import com.turfup.booking.RecurringBookingService;
import com.turfup.booking.RecurringBookingService.RecurringRequest;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.payment.PaymentsProperties;
import com.turfup.pricing.PricingService;
import com.turfup.settings.AppSettings;
import com.turfup.venue.CourtHours;
import com.turfup.venue.VenueCustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A busy-looking Kick Arena for showing TurfUp to turf owners (scripts/reset-demo, demo profile only): today's and
 * tomorrow's operating days filled with online (advance paid), walk-in and phone bookings, two regulars (Rahul Patil
 * with the advance waived and a standing Tue/Fri 8 PM slot; Sameer Khan, suggested for a waiver), a month of played
 * history behind them, one played booking with its balance still due, and a few free evening slots.
 *
 * <p>Everything is placed relative to the database clock when it runs, so reset shortly before the demo. Bookings go
 * through the real services (hold + simulated pay, owner entry, standing slots, balance received), except those a
 * service cannot create any more because their slot has already started: past history and today's online bookings
 * that have begun are inserted directly, in the same shape. Runs only on a venue with no bookings yet, and only in
 * simulated payments mode. Never part of the plain demo seed: the e2e walkthrough books the same venue.
 */
@Component
@Profile("demo")
public class DemoShowcase {

    public static final String PLAYER_PASSWORD = "player-demo-123";

    private static final Logger log = LoggerFactory.getLogger(DemoShowcase.class);

    private static final String MAIN = "Main turf";
    private static final String BADMINTON_1 = "Badminton 1";
    private static final String BADMINTON_2 = "Badminton 2";

    record Player(String name, String phone) {
    }

    static final Player RAHUL = new Player("Rahul Patil", "7000000001");
    static final Player SAMEER = new Player("Sameer Khan", "7000000002");
    static final Player ADITYA = new Player("Aditya Kulkarni", "7000000003");
    static final Player PRIYA = new Player("Priya Deshmukh", "7000000004");
    static final Player FAIZAN = new Player("Faizan Qureshi", "7000000005");
    static final Player SNEHA = new Player("Sneha Jadhav", "7000000006");
    static final Player OMKAR = new Player("Omkar Pawar", "7000000007");
    static final Player NEHA = new Player("Neha Joshi", "7000000008");
    private static final List<Player> PLAYERS = List.of(RAHUL, SAMEER, ADITYA, PRIYA, FAIZAN, SNEHA, OMKAR, NEHA);

    /** Rahul's standing slot: Tuesdays and Fridays, 8-9 PM on the main turf, running for the last four weeks. */
    static final int STANDING_START = 20 * 60;
    static final List<DayOfWeek> STANDING_DAYS = List.of(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY);

    /** Today's played booking whose balance is left for the owner to collect. */
    static final int BALANCE_DUE_HOUR = 8;

    private enum Kind { ONLINE, PHONE, WALKIN }

    /**
     * One one-hour booking, {@code day} 0 = today's operating day, 1 = tomorrow's; {@code hour} in operating-day
     * hours (24 = midnight). A {@code player} books online or is entered by phone; otherwise {@code name} (blank =
     * the owner skipped it).
     */
    private record Planned(int day, String court, int hour, Kind kind, Player player, String name, String phone) {
    }

    private static Planned online(int day, String court, int hour, Player p) {
        return new Planned(day, court, hour, Kind.ONLINE, p, null, null);
    }

    private static Planned phone(int day, String court, int hour, String name, String phone) {
        return new Planned(day, court, hour, Kind.PHONE, null, name, phone);
    }

    private static Planned phone(int day, String court, int hour, Player p) {
        return new Planned(day, court, hour, Kind.PHONE, p, null, null);
    }

    private static Planned walkin(int day, String court, int hour, String name) {
        return new Planned(day, court, hour, Kind.WALKIN, null, name, null);
    }

    // Main turf evenings left free: today 6 PM, 10 PM, midnight, 1 AM; tomorrow 7 PM, 10 PM, midnight, 1 AM.
    private static final List<Planned> PLAN = List.of(
            walkin(0, MAIN, 6, "Vishal Shinde"),
            online(0, MAIN, 7, SAMEER),
            phone(0, MAIN, BALANCE_DUE_HOUR, "Ganesh Wagh", "7000000021"),
            walkin(0, MAIN, 10, ""),
            phone(0, MAIN, 16, ADITYA),
            online(0, MAIN, 17, FAIZAN),
            online(0, MAIN, 19, RAHUL),
            phone(0, MAIN, 20, "Sachin More", "7000000022"),
            online(0, MAIN, 21, OMKAR),
            walkin(0, MAIN, 23, "Tushar Gaikwad"),
            online(0, BADMINTON_1, 7, PRIYA),
            walkin(0, BADMINTON_1, 18, "Anjali Kulkarni"),
            online(0, BADMINTON_1, 19, SNEHA),
            phone(0, BADMINTON_2, 6, "Mahesh Rao", "7000000023"),
            online(0, BADMINTON_2, 20, NEHA),

            online(1, MAIN, 6, SAMEER),
            online(1, MAIN, 7, ADITYA),
            phone(1, MAIN, 9, "Ganesh Wagh", "7000000021"),
            online(1, MAIN, 17, FAIZAN),
            phone(1, MAIN, 18, "Sachin More", "7000000022"),
            online(1, MAIN, 20, OMKAR),
            online(1, MAIN, 21, RAHUL),
            phone(1, MAIN, 23, "Akshay Deshpande", "7000000024"),
            online(1, BADMINTON_1, 8, PRIYA),
            online(1, BADMINTON_1, 18, SNEHA),
            phone(1, BADMINTON_2, 19, "Mahesh Rao", "7000000023"));

    /** Played online bookings behind the regulars list, by days before today. */
    private record History(Player player, String court, int hour, List<Integer> daysAgo) {
    }

    private static final List<History> HISTORY = List.of(
            new History(SAMEER, MAIN, 7, List.of(2, 4, 6, 9, 11, 13, 16, 20, 23)),
            new History(PRIYA, BADMINTON_1, 18, List.of(3, 7, 10, 14)),
            new History(ADITYA, MAIN, 19, List.of(7, 14, 21)),
            new History(FAIZAN, MAIN, 21, List.of(5, 12)));

    record Court(long id, String name, long advanceAmount) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final BookingService bookings;
    private final CheckoutService checkout;
    private final PaymentService payments;
    private final RecurringBookingService recurring;
    private final VenueCustomerService customers;
    private final PricingService pricing;
    private final PaymentsProperties paymentsProps;
    private final AppSettings settings;

    public DemoShowcase(JdbcClient jdbc, PasswordEncoder passwords, BookingService bookings, CheckoutService checkout,
                        PaymentService payments, RecurringBookingService recurring, VenueCustomerService customers,
                        PricingService pricing, PaymentsProperties paymentsProps, AppSettings settings) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.bookings = bookings;
        this.checkout = checkout;
        this.payments = payments;
        this.recurring = recurring;
        this.customers = customers;
        this.pricing = pricing;
        this.paymentsProps = paymentsProps;
        this.settings = settings;
    }

    /** Fills the demo owner's Kick Arena (see the class comment). Call after {@link DemoDataSeeder#seed()}. */
    public void seed() {
        if (paymentsProps.mode() != com.turfup.payment.PaymentMode.SIMULATED) {
            log.info("Demo showcase skipped: it needs simulated payments.");
            return;
        }
        record Venue(long id, long ownerId, int openMinute, int closeMinute, boolean approved, boolean booked) {
        }
        Venue venue = jdbc.sql("""
                        SELECT v.id, v.owner_id, v.open_minute, v.close_minute, v.approved,
                               EXISTS (SELECT 1 FROM bookings b JOIN courts c ON c.id = b.court_id
                                       WHERE c.venue_id = v.id) AS booked
                        FROM venues v JOIN users u ON u.id = v.owner_id
                        WHERE u.phone = :phone AND v.name = 'Kick Arena'
                        """)
                .param("phone", DemoDataSeeder.OWNER_PHONE)
                .query(Venue.class)
                .optional()
                .orElse(null);
        if (venue == null || !venue.approved()) {
            log.info("Demo showcase skipped: no approved Kick Arena for the demo owner.");
            return;
        }
        if (venue.booked()) {
            log.info("Demo showcase skipped: Kick Arena already has bookings (reset the demo database first).");
            return;
        }

        Map<String, Court> courts = new HashMap<>();
        jdbc.sql("SELECT id, name, advance_amount FROM courts WHERE venue_id = :venueId")
                .param("venueId", venue.id())
                .query(Court.class)
                .list()
                .forEach(c -> courts.put(c.name(), c));
        Map<Player, Long> players = new HashMap<>();
        for (Player p : PLAYERS) {
            players.put(p, player(p));
        }

        OffsetDateTime now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
        LocalDate today = new CourtHours(venue.openMinute(), venue.closeMinute(), 60).currentOperatingDate(now);
        var run = new Run(venue.id(), venue.ownerId(), courts, players, now, today,
                settings.getInt("complete_after_hours"));

        // Regulars first, so the waiver applies to Rahul's online bookings below.
        customers.setAdvanceWaived(venue.ownerId(), venue.id(), players.get(RAHUL), true);
        note(venue.id(), players.get(RAHUL), "Office team. Standing Tue & Fri 8 PM. Pays the balance by UPI.");
        note(venue.id(), players.get(SAMEER), "Morning group, books 7 AM most days.");

        // The standing slot generates its next four weeks; its past four weeks are history.
        LocalDate standingFrom = today.minusDays(28);
        for (DayOfWeek dow : STANDING_DAYS) {
            long id = recurring.create(venue.ownerId(), courts.get(MAIN).id(), new RecurringRequest(
                    players.get(RAHUL), dow.getValue(), STANDING_START, STANDING_START + 60, standingFrom, null, null))
                    .recurring().id();
            for (LocalDate d = standingFrom; !d.isAfter(today); d = d.plusDays(1)) {
                if (d.getDayOfWeek() == dow && at(d, STANDING_START / 60).isBefore(now)) {
                    run.history(courts.get(MAIN), d, STANDING_START / 60, BookingSource.PHONE, RAHUL, id);
                }
            }
        }
        for (History h : HISTORY) {
            for (int n : h.daysAgo()) {
                run.history(courts.get(h.court()), today.minusDays(n), h.hour(), BookingSource.ONLINE, h.player(),
                        null);
            }
        }
        for (Planned p : PLAN) {
            run.planned(p);
        }
        log.info("Demo showcase: {} bookings on Kick Arena for {} and {} (and {} played before), "
                        + "regulars Rahul Patil (waived, standing Tue/Fri 8 PM) and Sameer Khan; players log in with {}",
                run.planned, today, today.plusDays(1), run.history, PLAYER_PASSWORD);
    }

    /** State of one showcase run. */
    private final class Run {

        final long venueId;
        final long ownerId;
        final Map<String, Court> courts;
        final Map<Player, Long> players;
        final OffsetDateTime now;
        final LocalDate today;
        final int completeAfterHours;
        int planned;
        int history;
        boolean upi;

        Run(long venueId, long ownerId, Map<String, Court> courts, Map<Player, Long> players, OffsetDateTime now,
            LocalDate today, int completeAfterHours) {
            this.venueId = venueId;
            this.ownerId = ownerId;
            this.courts = courts;
            this.players = players;
            this.now = now;
            this.today = today;
            this.completeAfterHours = completeAfterHours;
        }

        void planned(Planned p) {
            Court court = courts.get(p.court());
            LocalDate date = today.plusDays(p.day());
            OffsetDateTime start = at(date, p.hour());
            if (taken(court, start)) {
                // Rahul's standing slot got there first (today or tomorrow is a Tuesday or Friday).
                return;
            }
            Long playerId = p.player() == null ? null : players.get(p.player());
            if (p.kind() == Kind.ONLINE && !start.isAfter(now.plusMinutes(1))) {
                // Booked online earlier; a hold can no longer be taken for a slot that has started.
                past(court, date, p.hour(), BookingSource.ONLINE, p.player());
            } else if (p.kind() == Kind.ONLINE) {
                Booking held = bookings.hold(court.id(), playerId, start, start.plusHours(1));
                if (held.getStatus() == BookingStatus.HELD) {
                    checkout.payAdvance(held.getId(), playerId);
                }
            } else {
                long id = bookings.enterForOwner(ownerId, court.id(), new OwnerEntry(start, start.plusHours(1),
                        p.kind() == Kind.WALKIN ? BookingSource.WALKIN : BookingSource.PHONE, playerId, p.name(),
                        p.phone())).getId();
                boolean leaveDue = p.day() == 0 && p.court().equals(MAIN) && p.hour() == BALANCE_DUE_HOUR;
                if (!leaveDue) {
                    collectIfPlayed(id, start);
                }
            }
            planned++;
        }

        void history(Court court, LocalDate date, int hour, BookingSource source, Player player, Long recurringId) {
            if (taken(court, at(date, hour))) {
                return;
            }
            past(court, date, hour, source, player, recurringId);
            history++;
        }

        private void past(Court court, LocalDate date, int hour, BookingSource source, Player player) {
            past(court, date, hour, source, player, null);
        }

        /**
         * A booking made earlier, as it would stand now: CONFIRMED, or COMPLETED once {@code complete_after_hours}
         * have passed since it ended; online ones with their advance paid (none if the player's advance is waived),
         * and the balance received once played. The caller checks the hour is free.
         */
        private void past(Court court, LocalDate date, int hour, BookingSource source, Player player,
                          Long recurringId) {
            OffsetDateTime start = at(date, hour);
            OffsetDateTime end = start.plusHours(1);
            long playerId = players.get(player);
            boolean waived = jdbc.sql("""
                            SELECT advance_waived FROM venue_customers WHERE venue_id = :venueId AND player_id = :playerId
                            """)
                    .param("venueId", venueId)
                    .param("playerId", playerId)
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
            long advance = source == BookingSource.ONLINE && !waived ? court.advanceAmount() : 0;
            long price = pricing.price(court.id(), date.getDayOfWeek(), hour * 60, hour * 60 + 60);
            boolean completed = !end.plusHours(completeAfterHours).isAfter(now);
            OffsetDateTime bookedAt = source == BookingSource.ONLINE ? start.minusDays(1) : start.minusHours(3);
            long id = jdbc.sql("""
                            INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price,
                                                  advance_required, recurring_id, created_at)
                            VALUES (:courtId, :playerId, :startAt, :endAt, :status, :source, :price, :advance,
                                    :recurringId, :createdAt)
                            RETURNING id
                            """)
                    .param("courtId", court.id())
                    .param("playerId", playerId)
                    .param("startAt", start)
                    .param("endAt", end)
                    .param("status", (completed ? BookingStatus.COMPLETED : BookingStatus.CONFIRMED).name())
                    .param("source", source.name())
                    .param("price", price)
                    .param("advance", advance)
                    .param("recurringId", recurringId)
                    .param("createdAt", bookedAt)
                    .query(Long.class)
                    .single();
            if (advance > 0) {
                jdbc.sql("""
                                INSERT INTO payments (booking_id, kind, amount, method, status, gateway_order_id,
                                                      gateway_payment_id, created_at)
                                VALUES (:id, 'ADVANCE', :amount, 'SIMULATED', 'SUCCEEDED', :orderId, :paymentId,
                                        :createdAt)
                                """)
                        .param("id", id)
                        .param("amount", advance)
                        .param("orderId", "sim_order_" + UUID.randomUUID())
                        .param("paymentId", "sim_pay_" + UUID.randomUUID())
                        .param("createdAt", bookedAt)
                        .update();
            }
            collectIfPlayed(id, start);
        }

        /** The owner received the balance of a slot that has ended (Rahul pays by UPI, others take turns). */
        private void collectIfPlayed(long bookingId, OffsetDateTime start) {
            if (bookingId < 0 || start.plusHours(1).isAfter(now)) {
                return;
            }
            boolean due = jdbc.sql("SELECT total_price > advance_required FROM bookings WHERE id = :id")
                    .param("id", bookingId)
                    .query(Boolean.class)
                    .single();
            if (!due) {
                return;
            }
            boolean rahul = jdbc.sql("SELECT player_id IS NOT DISTINCT FROM :rahul FROM bookings WHERE id = :id")
                    .param("rahul", players.get(RAHUL))
                    .param("id", bookingId)
                    .query(Boolean.class)
                    .single();
            upi = !upi;
            payments.recordBalanceReceived(ownerId, bookingId, rahul || upi ? PaymentMethod.UPI : PaymentMethod.CASH);
        }

        /** Whether the court already has a booking (active or played) overlapping this hour. */
        private boolean taken(Court court, OffsetDateTime start) {
            return jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM bookings
                                           WHERE court_id = :courtId AND status IN ('HELD', 'CONFIRMED', 'COMPLETED')
                                             AND tstzrange(start_at, end_at) && tstzrange(:startAt, :endAt))
                            """)
                    .param("courtId", court.id())
                    .param("startAt", start)
                    .param("endAt", start.plusHours(1))
                    .query(Boolean.class)
                    .single();
        }
    }

    private static OffsetDateTime at(LocalDate operatingDate, int hour) {
        return operatingDate.atStartOfDay(CourtHours.IST).plusHours(hour).toOffsetDateTime();
    }

    private long player(Player p) {
        return jdbc.sql("SELECT id FROM users WHERE phone = :phone")
                .param("phone", p.phone())
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                                INSERT INTO users (name, phone, password_hash, role)
                                VALUES (:name, :phone, :hash, 'PLAYER')
                                RETURNING id
                                """)
                        .param("name", p.name())
                        .param("phone", p.phone())
                        .param("hash", passwords.encode(PLAYER_PASSWORD))
                        .query(Long.class)
                        .single());
    }

    private void note(long venueId, long playerId, String notes) {
        jdbc.sql("""
                        INSERT INTO venue_customers (venue_id, player_id, notes) VALUES (:venueId, :playerId, :notes)
                        ON CONFLICT (venue_id, player_id) DO UPDATE SET notes = EXCLUDED.notes
                        """)
                .param("venueId", venueId)
                .param("playerId", playerId)
                .param("notes", notes)
                .update();
    }
}
