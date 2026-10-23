package com.turfup.booking;

import com.turfup.auth.TokenService;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.payment.PaymentService.BalanceReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Owner actions on bookings: walk-in/phone entry, no-show, balance received, cancel. */
@RestController
@RequestMapping("/api/owner")
class OwnerBookingController {

    record EntryRequest(
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            @NotNull BookingSource source,
            Long playerId,
            @Size(max = 120) String walkinName,
            @Size(max = 20) String walkinPhone) {
    }

    record BalanceRequest(@NotNull PaymentMethod method) {
    }

    private final BookingService bookings;
    private final PaymentService payments;
    private final CancellationService cancellations;
    private final OwnerScheduleService schedules;

    OwnerBookingController(BookingService bookings, PaymentService payments, CancellationService cancellations,
                           OwnerScheduleService schedules) {
        this.bookings = bookings;
        this.payments = payments;
        this.cancellations = cancellations;
        this.schedules = schedules;
    }

    /** One operating day at the owner's venue: a column per court with all slots, past ones included, and bookings. */
    @GetMapping("/venues/{venueId}/schedule")
    OwnerScheduleService.Schedule schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return schedules.schedule(TokenService.userId(jwt), venueId, date);
    }

    record CancelRequest(@NotNull CancelReason reason, @Size(max = 500) String note) {
    }

    /** Owner cancellation with a reason the player sees: always a full refund of everything paid. */
    @PostMapping("/bookings/{bookingId}/cancel")
    CancellationService.CancellationResult cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                                                  @Valid @RequestBody CancelRequest req) {
        return cancellations.cancelByOwner(TokenService.userId(jwt), bookingId, req.reason(), req.note());
    }

    @PostMapping("/courts/{courtId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView enter(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId, @Valid @RequestBody EntryRequest req) {
        OwnerEntry entry = new OwnerEntry(req.startAt(), req.endAt(), req.source(), req.playerId(),
                req.walkinName(), req.walkinPhone());
        return BookingView.of(bookings.enterForOwner(TokenService.userId(jwt), courtId, entry));
    }

    @PostMapping("/bookings/{bookingId}/no-show")
    BookingView noShow(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return BookingView.of(bookings.markNoShow(TokenService.userId(jwt), bookingId));
    }

    @PostMapping("/bookings/{bookingId}/balance")
    BalanceReceipt balance(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                           @Valid @RequestBody BalanceRequest req) {
        return payments.recordBalanceReceived(TokenService.userId(jwt), bookingId, req.method());
    }
}
