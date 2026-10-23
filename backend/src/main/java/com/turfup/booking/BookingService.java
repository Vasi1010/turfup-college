package com.turfup.booking;

import com.turfup.auth.PlayerNotFoundException;
import com.turfup.payment.LinkedAccountService;
import com.turfup.payment.OnlinePaymentUnavailableException;
import com.turfup.pricing.PriceNotConfiguredException;
import com.turfup.pricing.PricingService;
import com.turfup.settings.AppSettings;
import com.turfup.venue.CourtHours;
import com.turfup.venue.CourtHours.Booker;
import com.turfup.venue.CourtHoursRepository;
import com.turfup.venue.CourtHoursRepository.PublicCourtHours;
import com.turfup.venue.CourtNotFoundException;
import com.turfup.venue.InvalidSlotException;
import com.turfup.venue.OperatingSlot;
import com.turfup.venue.VenueNotFoundException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Every booking insert and status change. Each public method is one transaction.
 *
 * <p>Insert paths ({@link #hold} and {@link #enterForOwner}) share one sequence: lock the court row,
 * validate the slot against the database clock, the venue's hours and the court's slot length, price it, expire
 * stale holds that would block it, then insert. The exclusion constraint (per court) and the one-hold-per-player
 * index are the guarantee; their violations become domain exceptions. The court lock queues concurrent inserts on
 * one court, because inserts that conflict on an exclusion constraint can otherwise deadlock each other (see
 * docs/dbms.md). Booking paths lock only the court, never its venue (lock order: venue, court, rows).
 */
@Service
public class BookingService {

    static final String NO_OVERLAP_CONSTRAINT = "bookings_no_overlap";
    static final String ONE_HOLD_PER_PLAYER_INDEX = "bookings_one_active_hold_per_player";

    static final String DEFAULT_WALKIN_NAME = "Walk-in";
    static final String DEFAULT_PHONE_NAME = "Phone booking";

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String UNIQUE_VIOLATION = "23505";

    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final AppSettings settings;
    private final PricingService pricing;
    private final LinkedAccountService linkedAccounts;
    private final CourtHoursRepository courts;
    private final TransactionTemplate tx;

    public BookingService(JdbcClient jdbc, BookingRepository bookings, AppSettings settings,
                          PricingService pricing, LinkedAccountService linkedAccounts, CourtHoursRepository courts,
                          PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.settings = settings;
        this.pricing = pricing;
        this.linkedAccounts = linkedAccounts;
        this.courts = courts;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The court row as read under the booking lock, with its venue's hours and the database clock of this
     * transaction.
     */
    record LockedCourt(long venueId, int openMinute, int closeMinute, int slotMinutes, long advanceAmount,
                       OffsetDateTime dbNow) {

        CourtHours hours() {
            return new CourtHours(openMinute, closeMinute, slotMinutes);
        }
    }

    /**
     * A booking entered by the owner for someone at the counter or on the phone: a registered
     * {@code playerId}, or a {@code walkinName} (saved as "Walk-in" / "Phone booking" if left blank).
     */
    public record OwnerEntry(OffsetDateTime startAt, OffsetDateTime endAt, BookingSource source, Long playerId,
                             String walkinName, String walkinPhone) {
    }

    /**
     * Books {@code [startAt, endAt)} on one court for an online player. Normally the booking is HELD for
     * {@code hold_minutes} until the advance is paid. If the venue waives the advance for this player, or the
     * court's advance is zero, it is CONFIRMED immediately with {@code advance_required = 0}.
     * A hold that needs an online advance is refused while the venue cannot take one (razorpay mode, linked
     * account not active); waived and zero-advance bookings still go through.
     *
     * @throws SlotUnavailableException     if the slot overlaps an active booking on this court
     * @throws ActiveHoldExistsException    if the player already has an active hold
     * @throws InvalidSlotException         if the slot is in the past, outside opening hours or misaligned
     * @throws PriceNotConfiguredException  if part of the slot has no price rule
     * @throws CourtNotFoundException       if the court does not exist or is not public (it and its venue active,
     *                                      the venue approved)
     * @throws OnlinePaymentUnavailableException if an advance is due but the venue cannot take online payments
     * @throws IllegalArgumentException     if the range is empty
     */
    @Transactional
    public Booking hold(long courtId, long playerId, OffsetDateTime startAt, OffsetDateTime endAt) {
        return holdLocked(courtId, playerId, startAt, endAt);
    }

    /**
     * "Any free court": holds {@code [startAt, endAt)} on whichever of the venue's courts for {@code sport} is
     * free, trying the cheapest first (then display order). Each attempt is its own transaction holding one court
     * lock, so concurrent players are spread over the courts without lock cycles; a court taken in the meantime
     * moves on to the next.
     *
     * @throws SlotUnavailableException if every matching court is taken
     * @throws VenueNotFoundException   if no public court of the venue offers the sport
     * @throws IllegalArgumentException if the sport's courts do not share a slot length, or the range is empty
     * @throws ActiveHoldExistsException, OnlinePaymentUnavailableException, InvalidSlotException as {@link #hold}
     */
    public Booking holdAnyCourt(long venueId, String sport, long playerId, OffsetDateTime startAt,
                                OffsetDateTime endAt) {
        String code = sport == null ? "" : sport.strip().toLowerCase(Locale.ROOT);
        List<PublicCourtHours> candidates = courts.findPublicForSport(venueId, code);
        if (candidates.isEmpty()) {
            throw new VenueNotFoundException(venueId);
        }
        int slotMinutes = candidates.getFirst().hours().slotMinutes();
        if (candidates.stream().anyMatch(c -> c.hours().slotMinutes() != slotMinutes)) {
            throw new IllegalArgumentException("These courts have different slot lengths. Please pick a court.");
        }

        record Candidate(long courtId, long price, int order) {
        }
        List<Candidate> ordered = new ArrayList<>();
        InvalidSlotException invalid = null;
        for (int i = 0; i < candidates.size(); i++) {
            PublicCourtHours c = candidates.get(i);
            try {
                // Shape only (hours, alignment) to rank the courts; the clock is checked under the court lock.
                OperatingSlot slot = c.hours().validateSlot(startAt, endAt, startAt.minusNanos(1), Booker.PLAYER);
                OptionalLong price = pricing.forDay(c.courtId(), slot.dayOfWeek()).find(slot.startMinute(), slot.endMinute());
                if (price.isPresent()) {
                    ordered.add(new Candidate(c.courtId(), price.getAsLong(), i));
                }
            } catch (InvalidSlotException e) {
                invalid = e;
            }
        }
        if (ordered.isEmpty()) {
            throw invalid != null ? invalid : new PriceNotConfiguredException();
        }
        ordered.sort(Comparator.comparingLong(Candidate::price).thenComparingInt(Candidate::order));

        SlotUnavailableException taken = null;
        for (Candidate c : ordered) {
            try {
                return tx.execute(status -> holdLocked(c.courtId(), playerId, startAt, endAt));
            } catch (SlotUnavailableException e) {
                taken = e;
            }
        }
        throw taken;
    }

    private Booking holdLocked(long courtId, long playerId, OffsetDateTime startAt, OffsetDateTime endAt) {
        LockedCourt court = lockCourt(courtId, null);
        long totalPrice = validateAndPrice(courtId, court, startAt, endAt, Booker.PLAYER);
        boolean waived = jdbc.sql("""
                        SELECT advance_waived FROM venue_customers
                        WHERE venue_id = :venueId AND player_id = :playerId
                        """)
                .param("venueId", court.venueId())
                .param("playerId", playerId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        expireStaleHolds(courtId, startAt, endAt, playerId);

        if (waived || court.advanceAmount() == 0) {
            return insert(courtId, playerId, null, null, startAt, endAt, BookingStatus.CONFIRMED,
                    BookingSource.ONLINE, totalPrice, 0, null, null);
        }
        if (!linkedAccounts.acceptsOnlineAdvance(court.venueId())) {
            throw new OnlinePaymentUnavailableException();
        }
        return insert(courtId, playerId, null, null, startAt, endAt, BookingStatus.HELD,
                BookingSource.ONLINE, totalPrice, court.advanceAmount(), settings.holdMinutes(), null);
    }

    /**
     * Owner entry for a walk-in or phone booking: CONFIRMED immediately, no advance (the full price is
     * paid at the venue). Same slot checks and pricing as {@link #hold}, except that any slot on the
     * current operating day is allowed, even one already started or finished (see
     * {@link Booker#OWNER}). Works on the owner's courts even while the court is paused or the venue is
     * inactive or unapproved.
     *
     * @throws CourtNotFoundException   if the court does not exist or is at another owner's venue
     * @throws PlayerNotFoundException  if {@code playerId} is given but is not a player
     * @throws SlotUnavailableException if the slot overlaps an active booking
     * @throws IllegalArgumentException if the source is ONLINE
     */
    @Transactional
    public Booking enterForOwner(long ownerId, long courtId, OwnerEntry entry) {
        if (entry.source() != BookingSource.WALKIN && entry.source() != BookingSource.PHONE) {
            throw new IllegalArgumentException("Owner entries must be WALKIN or PHONE");
        }
        String walkinName = blankToNull(entry.walkinName());
        if (entry.playerId() == null && walkinName == null) {
            // Two-tap entry: the owner may skip the name.
            walkinName = entry.source() == BookingSource.WALKIN ? DEFAULT_WALKIN_NAME : DEFAULT_PHONE_NAME;
        }
        LockedCourt court = lockCourt(courtId, ownerId);
        if (entry.playerId() != null) {
            jdbc.sql("SELECT 1 FROM users WHERE id = :playerId AND role = 'PLAYER'")
                    .param("playerId", entry.playerId())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> new PlayerNotFoundException(entry.playerId()));
        }
        long totalPrice = validateAndPrice(courtId, court, entry.startAt(), entry.endAt(), Booker.OWNER);
        expireStaleHolds(courtId, entry.startAt(), entry.endAt(), null);
        return insert(courtId, entry.playerId(), walkinName, blankToNull(entry.walkinPhone()),
                entry.startAt(), entry.endAt(), BookingStatus.CONFIRMED, entry.source(), totalPrice, 0, null, null);
    }

    /**
     * Records that the player started paying the advance for their HELD booking. The first attempt's
     * time is kept: it is what entitles the payment to the grace window after the hold expires.
     *
     * @return the advance to charge, in paise
     * @throws BookingNotFoundException       if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException   if the booking is not HELD
     * @throws PaymentWindowClosedException   if less than {@code min_pay_seconds_remaining} is left
     */
    @Transactional
    public long startPayment(long bookingId, long playerId) {
        record Row(String status, long advanceRequired, boolean canPay) {
        }
        Row row = jdbc.sql("""
                        SELECT status, advance_required,
                               COALESCE(hold_expires_at - now() >= make_interval(secs => :minSeconds), false)
                                   AS can_pay
                        FROM bookings
                        WHERE id = :id AND player_id = :playerId
                        FOR UPDATE
                        """)
                .param("id", bookingId)
                .param("playerId", playerId)
                .param("minSeconds", settings.minPaySecondsRemaining())
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!row.status().equals(BookingStatus.HELD.name())) {
            throw new InvalidBookingStateException("This booking is not waiting for payment.");
        }
        if (!row.canPay()) {
            throw new PaymentWindowClosedException();
        }
        jdbc.sql("UPDATE bookings SET payment_started_at = COALESCE(payment_started_at, now()) WHERE id = :id")
                .param("id", bookingId)
                .update();
        return row.advanceRequired();
    }

    /**
     * Called when an advance payment has been captured. Confirms the booking if it is still HELD and
     * not expired (a hold whose payment started in time stays valid through the grace window) and the
     * amount matches. Otherwise the booking is not confirmed and the caller must refund; an expired
     * hold is marked EXPIRED.
     *
     * @return true if the booking is now CONFIRMED by this payment
     * @throws BookingNotFoundException if the booking does not exist
     */
    @Transactional
    public boolean confirmPaidHold(long bookingId, long amountPaid) {
        record Row(String status, long advanceRequired, boolean expired) {
        }
        Row row = jdbc.sql("""
                        SELECT status, advance_required, (status = 'HELD' AND %s) AS expired
                        FROM bookings
                        WHERE id = :id
                        FOR UPDATE
                        """.formatted(HoldExpirySql.IS_EXPIRED))
                .param("id", bookingId)
                .param("graceMinutes", settings.graceMinutes())
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!row.status().equals(BookingStatus.HELD.name())) {
            return false;
        }
        if (row.expired()) {
            setStatus(bookingId, BookingStatus.EXPIRED);
            return false;
        }
        if (amountPaid != row.advanceRequired()) {
            return false;
        }
        setStatus(bookingId, BookingStatus.CONFIRMED);
        return true;
    }

    /**
     * Owner marks a CONFIRMED booking as NO_SHOW once its slot has started. The database trigger
     * {@code bookings_count_no_show} increments the player's {@code no_show_count} at this venue.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is at another owner's venue
     * @throws InvalidBookingStateException if the booking is not CONFIRMED or has not started yet
     */
    @Transactional
    public Booking markNoShow(long ownerId, long bookingId) {
        record Row(String status, boolean started) {
        }
        Row row = jdbc.sql("""
                        SELECT b.status, b.start_at <= now() AS started
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :id AND v.owner_id = :ownerId
                        FOR UPDATE OF b
                        """)
                .param("id", bookingId)
                .param("ownerId", ownerId)
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!row.status().equals(BookingStatus.CONFIRMED.name())) {
            throw new InvalidBookingStateException("Only a confirmed booking can be marked as a no-show.");
        }
        if (!row.started()) {
            throw new InvalidBookingStateException("A booking can be marked as a no-show only after its slot starts.");
        }
        setStatus(bookingId, BookingStatus.NO_SHOW);
        return bookings.findById(bookingId).orElseThrow();
    }

    // --- shared steps ---

    /**
     * Locks the court row (only the court: its venue is read, not locked). Online bookings
     * ({@code ownerId == null}) need a public court; owner entries need a court at the owner's own venue.
     * NO KEY UPDATE does not block the KEY SHARE locks taken by foreign keys.
     */
    private LockedCourt lockCourt(long courtId, Long ownerId) {
        return lockCourt(courtId, ownerId, ownerId == null ? "c.active AND v.active AND v.approved" : "v.owner_id = :ownerId");
    }

    /** The same court lock for system jobs (recurring generation), on any court. */
    LockedCourt lockCourtForJob(long courtId) {
        return lockCourt(courtId, null, "TRUE");
    }

    private LockedCourt lockCourt(long courtId, Long ownerId, String condition) {
        var spec = jdbc.sql("""
                        SELECT c.venue_id, v.open_minute, v.close_minute, c.slot_minutes, c.advance_amount,
                               now() AS db_now
                        FROM courts c JOIN venues v ON v.id = c.venue_id
                        WHERE c.id = :courtId AND %s
                        FOR NO KEY UPDATE OF c
                        """.formatted(condition))
                .param("courtId", courtId);
        if (ownerId != null) {
            spec = spec.param("ownerId", ownerId);
        }
        return spec.query(LockedCourt.class)
                .optional()
                .orElseThrow(() -> new CourtNotFoundException(courtId));
    }

    private long validateAndPrice(long courtId, LockedCourt court, OffsetDateTime startAt, OffsetDateTime endAt,
                                  Booker booker) {
        OperatingSlot slot = court.hours().validateSlot(startAt, endAt, court.dbNow(), booker);
        return pricing.price(courtId, slot.dayOfWeek(), slot.startMinute(), slot.endMinute());
    }

    /**
     * Constraints cannot use now(), so expired HELD rows still count as active until marked EXPIRED.
     * Expires those overlapping the new booking, and the player's own expired hold (one hold per player).
     */
    void expireStaleHolds(long courtId, OffsetDateTime startAt, OffsetDateTime endAt, Long playerId) {
        jdbc.sql("""
                        UPDATE bookings SET status = 'EXPIRED'
                        WHERE status = 'HELD'
                          AND %s
                          AND ((court_id = :courtId AND tstzrange(start_at, end_at) && tstzrange(:startAt, :endAt))
                               OR player_id = CAST(:playerId AS bigint))
                        """.formatted(HoldExpirySql.IS_EXPIRED))
                .param("graceMinutes", settings.graceMinutes())
                .param("courtId", courtId)
                .param("startAt", startAt)
                .param("endAt", endAt)
                .param("playerId", playerId)
                .update();
    }

    /**
     * Whether an active booking (CONFIRMED, or HELD and not expired) overlaps {@code [startAt, endAt)}. Call with
     * the court lock held, after {@link #expireStaleHolds}, so the answer cannot change before an insert.
     */
    boolean slotTaken(long courtId, OffsetDateTime startAt, OffsetDateTime endAt) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM bookings
                            WHERE court_id = :courtId AND status IN ('HELD', 'CONFIRMED')
                              AND tstzrange(start_at, end_at) && tstzrange(:startAt, :endAt))
                        """)
                .param("courtId", courtId)
                .param("startAt", startAt)
                .param("endAt", endAt)
                .query(Boolean.class)
                .single();
    }

    Booking insert(long courtId, Long playerId, String walkinName, String walkinPhone,
                   OffsetDateTime startAt, OffsetDateTime endAt, BookingStatus status, BookingSource source,
                   long totalPrice, long advanceRequired, Integer holdMinutes, Long recurringId) {
        long id;
        try {
            id = jdbc.sql("""
                            INSERT INTO bookings (court_id, player_id, walkin_name, walkin_phone, start_at, end_at,
                                                  status, source, total_price, advance_required, hold_expires_at,
                                                  recurring_id)
                            VALUES (:courtId, :playerId, :walkinName, :walkinPhone, :startAt, :endAt,
                                    :status, :source, :totalPrice, :advanceRequired,
                                    now() + make_interval(mins => CAST(:holdMinutes AS integer)), :recurringId)
                            RETURNING id
                            """)
                    .param("courtId", courtId)
                    .param("playerId", playerId)
                    .param("walkinName", walkinName)
                    .param("walkinPhone", walkinPhone)
                    .param("startAt", startAt)
                    .param("endAt", endAt)
                    .param("status", status.name())
                    .param("source", source.name())
                    .param("totalPrice", totalPrice)
                    .param("advanceRequired", advanceRequired)
                    .param("holdMinutes", holdMinutes)
                    .param("recurringId", recurringId)
                    .query(Long.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, courtId, playerId);
        }
        return bookings.findById(id).orElseThrow();
    }

    private void setStatus(long bookingId, BookingStatus status) {
        jdbc.sql("UPDATE bookings SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", bookingId)
                .update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static RuntimeException translate(DataIntegrityViolationException e, long courtId, Long playerId) {
        PSQLException pg = findCause(e, PSQLException.class);
        if (pg == null) {
            return e;
        }
        ServerErrorMessage server = pg.getServerErrorMessage();
        String constraint = server == null ? null : server.getConstraint();
        if (EXCLUSION_VIOLATION.equals(pg.getSQLState()) && NO_OVERLAP_CONSTRAINT.equals(constraint)) {
            return new SlotUnavailableException();
        }
        if (UNIQUE_VIOLATION.equals(pg.getSQLState()) && ONE_HOLD_PER_PLAYER_INDEX.equals(constraint)
                && playerId != null) {
            return new ActiveHoldExistsException(playerId);
        }
        return e;
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
