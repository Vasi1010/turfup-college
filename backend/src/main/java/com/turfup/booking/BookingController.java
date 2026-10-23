package com.turfup.booking;

import com.turfup.auth.TokenService;
import com.turfup.booking.CancellationService.CancellationResult;
import com.turfup.booking.CancellationService.CancellationTerms;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.CheckoutService.PayResult;
import com.turfup.settings.AppSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** Player booking flow: hold a slot, pay the advance, cancel or release. */
@RestController
@RequestMapping("/api/bookings")
class BookingController {

    /** One court ({@code courtId}), or "any free court" ({@code venueId} and {@code sport}). */
    record HoldRequest(Long courtId, Long venueId, String sport, @NotNull OffsetDateTime startAt,
                       @NotNull OffsetDateTime endAt) {
    }

    /** {@code expectedRefund}: the refund (paise) shown to the player; omit to accept whatever applies. */
    record CancelRequest(Long expectedRefund) {
    }

    private final BookingService bookings;
    private final CheckoutService checkout;
    private final CancellationService cancellations;
    private final MyBookingsService mine;
    private final AppSettings settings;

    BookingController(BookingService bookings, CheckoutService checkout, CancellationService cancellations,
                      MyBookingsService mine, AppSettings settings) {
        this.bookings = bookings;
        this.checkout = checkout;
        this.cancellations = cancellations;
        this.mine = mine;
        this.settings = settings;
    }

    @GetMapping("/mine")
    List<MyBookingsService.MyBooking> mine(@AuthenticationPrincipal Jwt jwt) {
        return mine.list(TokenService.userId(jwt));
    }

    @GetMapping("/{bookingId}")
    MyBookingsService.MyBooking one(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return mine.get(TokenService.userId(jwt), bookingId);
    }

    /**
     * HELD with {@code holdExpiresAt} and {@code payCutoffAt} for the countdown, or CONFIRMED at once if the
     * advance is waived. {@code courtId} in the response is the court the slot was held on.
     */
    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest req) {
        long playerId = TokenService.userId(jwt);
        Booking held;
        if (req.courtId() != null) {
            held = bookings.hold(req.courtId(), playerId, req.startAt(), req.endAt());
        } else if (req.venueId() != null && req.sport() != null && !req.sport().isBlank()) {
            held = bookings.holdAnyCourt(req.venueId(), req.sport(), playerId, req.startAt(), req.endAt());
        } else {
            throw new IllegalArgumentException("Pick a court, or a venue and a sport.");
        }
        return BookingView.forPlayer(held, settings.minPaySecondsRemaining());
    }

    @PostMapping("/{bookingId}/pay")
    PayResult pay(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return checkout.payAdvance(bookingId, TokenService.userId(jwt));
    }

    /** What cancelling would refund right now; shown before the player confirms. */
    @GetMapping("/{bookingId}/cancellation")
    CancellationTerms cancellationQuote(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return cancellations.quote(bookingId, TokenService.userId(jwt));
    }

    @PostMapping("/{bookingId}/cancel")
    CancellationResult cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                              @RequestBody(required = false) CancelRequest req) {
        Long expected = req == null ? null : req.expectedRefund();
        return cancellations.cancelByPlayer(bookingId, TokenService.userId(jwt), expected);
    }

    /** Give up a HELD slot before paying. */
    @PostMapping("/{bookingId}/release")
    BookingView release(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return cancellations.releaseHold(bookingId, TokenService.userId(jwt));
    }
}
