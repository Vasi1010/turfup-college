package com.turfup.booking;

import com.turfup.pricing.DayPricing;
import com.turfup.pricing.PricingService;
import com.turfup.settings.AppSettings;
import com.turfup.venue.CourtHours;
import com.turfup.venue.OperatingSlot;
import com.turfup.venue.VenueNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The owner's view of one operating day at a venue: one column per court with every slot (past ones included),
 * and every booking on it, with who booked and what is still owed. Money is paise.
 */
@Service
public class OwnerScheduleService {

    public record ScheduleSlot(OffsetDateTime start, OffsetDateTime end, Long price, Long bookingId) {
    }

    /** One court's column. Paused courts are included: the owner still sees and enters their bookings. */
    public record CourtColumn(long courtId, String name, List<String> sports, int slotMinutes, boolean active,
                              List<ScheduleSlot> slots) {
    }

    /**
     * {@code balanceDue} is what the owner should collect at the venue (0 once received);
     * {@code balanceMethod} is CASH or UPI once received. {@code paymentClaimedAt} (upi mode): a HELD booking whose
     * player says they paid the advance; the owner checks it ({@code UpiPaymentService}).
     */
    public record ScheduleBooking(
            long id,
            long courtId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            String source,
            Long playerId,
            String customerName,
            String customerPhone,
            long totalPrice,
            long advanceRequired,
            long balanceDue,
            String balanceMethod,
            OffsetDateTime holdExpiresAt,
            Long recurringId,
            OffsetDateTime paymentClaimedAt) {
    }

    /** A standing-slot occurrence the job could not book on this day, for the owner to sort out. */
    public record RecurringSkip(long recurringId, long courtId, long playerId, String playerName, String playerPhone,
                                OffsetDateTime startAt, OffsetDateTime endAt, String reason) {
    }

    public record Schedule(
            long venueId,
            String venueName,
            LocalDate date,
            LocalDate currentOperatingDate,
            int openMinute,
            int closeMinute,
            OffsetDateTime serverNow,
            List<CourtColumn> courts,
            List<ScheduleBooking> bookings,
            List<RecurringSkip> recurringSkips) {
    }

    record VenueRow(String name, int openMinute, int closeMinute, OffsetDateTime dbNow) {
    }

    record CourtRow(long id, String name, int slotMinutes, boolean active) {
    }

    private final JdbcClient jdbc;
    private final PricingService pricing;
    private final AppSettings settings;

    public OwnerScheduleService(JdbcClient jdbc, PricingService pricing, AppSettings settings) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.settings = settings;
    }

    /**
     * Bookings shown: CONFIRMED, NO_SHOW, COMPLETED, and HELD holds that have not expired. Cancelled and
     * expired bookings no longer occupy the slot and are left out.
     *
     * @throws VenueNotFoundException if the venue does not exist or belongs to another owner
     */
    @Transactional(readOnly = true)
    public Schedule schedule(long ownerId, long venueId, LocalDate date) {
        VenueRow venue = jdbc.sql("""
                        SELECT name, open_minute, close_minute, now() AS db_now FROM venues
                        WHERE id = :venueId AND owner_id = :ownerId
                        """)
                .param("venueId", venueId)
                .param("ownerId", ownerId)
                .query(VenueRow.class)
                .optional()
                .orElseThrow(() -> new VenueNotFoundException(venueId));
        OffsetDateTime from = date.atStartOfDay(CourtHours.IST).plusMinutes(venue.openMinute()).toOffsetDateTime();
        OffsetDateTime to = date.atStartOfDay(CourtHours.IST).plusMinutes(venue.closeMinute()).toOffsetDateTime();

        List<ScheduleBooking> bookings = jdbc.sql("""
                        SELECT b.id, b.court_id, b.start_at, b.end_at, b.status, b.source, b.player_id,
                               COALESCE(u.name, b.walkin_name) AS customer_name,
                               COALESCE(u.phone, b.walkin_phone) AS customer_phone,
                               b.total_price, b.advance_required,
                               CASE WHEN bal.method IS NULL THEN b.total_price - b.advance_required ELSE 0 END
                                   AS balance_due,
                               bal.method AS balance_method,
                               b.hold_expires_at, b.recurring_id, b.payment_claimed_at
                        FROM bookings b
                        JOIN courts c ON c.id = b.court_id
                        LEFT JOIN users u ON u.id = b.player_id
                        LEFT JOIN payments bal ON bal.booking_id = b.id AND bal.kind = 'BALANCE'
                                              AND bal.status = 'SUCCEEDED'
                        WHERE c.venue_id = :venueId
                          AND tstzrange(b.start_at, b.end_at) && tstzrange(:from, :to)
                          AND (b.status IN ('CONFIRMED', 'NO_SHOW', 'COMPLETED')
                               OR (b.status = 'HELD' AND NOT %s))
                        ORDER BY b.start_at, b.court_id
                        """.formatted(HoldExpirySql.IS_EXPIRED))
                .param("venueId", venueId)
                .param("from", from)
                .param("to", to)
                .param("graceMinutes", settings.graceMinutes())
                .query(ScheduleBooking.class)
                .list();

        List<RecurringSkip> skips = jdbc.sql("""
                        SELECT s.recurring_id, r.court_id, r.player_id, u.name AS player_name, u.phone AS player_phone,
                               s.start_at, s.end_at, s.reason
                        FROM recurring_skips s
                        JOIN recurring_bookings r ON r.id = s.recurring_id
                        JOIN courts c ON c.id = r.court_id
                        JOIN users u ON u.id = r.player_id
                        WHERE c.venue_id = :venueId AND r.ended_at IS NULL
                          AND s.start_at >= :from AND s.start_at < :to
                        ORDER BY s.start_at, r.court_id
                        """)
                .param("venueId", venueId)
                .param("from", from)
                .param("to", to)
                .query(RecurringSkip.class)
                .list();

        List<CourtRow> courtRows = jdbc.sql("""
                        SELECT id, name, slot_minutes, active FROM courts WHERE venue_id = :venueId
                        ORDER BY sort_order, id
                        """)
                .param("venueId", venueId)
                .query(CourtRow.class)
                .list();
        List<CourtColumn> courts = new ArrayList<>();
        for (CourtRow c : courtRows) {
            CourtHours hours = new CourtHours(venue.openMinute(), venue.closeMinute(), c.slotMinutes());
            DayPricing prices = pricing.forDay(c.id(), date.getDayOfWeek());
            List<ScheduleSlot> slots = new ArrayList<>();
            for (OperatingSlot slot : hours.slotsOn(date)) {
                OptionalLong price = prices.find(slot.startMinute(), slot.endMinute());
                Long bookingId = bookings.stream()
                        .filter(b -> b.courtId() == c.id()
                                && b.startAt().isBefore(slot.endAt()) && slot.startAt().isBefore(b.endAt()))
                        .map(ScheduleBooking::id)
                        .findFirst()
                        .orElse(null);
                slots.add(new ScheduleSlot(slot.startAt(), slot.endAt(),
                        price.isPresent() ? price.getAsLong() : null, bookingId));
            }
            courts.add(new CourtColumn(c.id(), c.name(), sportsOf(c.id()), c.slotMinutes(), c.active(), slots));
        }

        CourtHours venueHours = new CourtHours(venue.openMinute(), venue.closeMinute(), 1);
        return new Schedule(venueId, venue.name(), date, venueHours.currentOperatingDate(venue.dbNow()),
                venue.openMinute(), venue.closeMinute(), venue.dbNow(), courts, bookings, skips);
    }

    private List<String> sportsOf(long courtId) {
        return jdbc.sql("""
                        SELECT cs.sport FROM court_sports cs JOIN sports s ON s.code = cs.sport
                        WHERE cs.court_id = :courtId ORDER BY s.sort_order
                        """)
                .param("courtId", courtId)
                .query(String.class)
                .list();
    }
}
