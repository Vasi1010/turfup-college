package com.turfup.payment;

import com.turfup.auth.TokenService;
import com.turfup.booking.BookingView;
import com.turfup.payment.UpiPaymentService.Inbox;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Direct UPI advances (upi mode): the player's "I've paid" and the owner's checks. See {@link UpiPaymentService}. */
@RestController
class UpiPaymentController {

    /** {@code ref}: the UPI reference (UTR) from the player's receipt, optional. */
    record ClaimRequest(@Size(max = 40) String ref) {
    }

    private final UpiPaymentService upi;

    UpiPaymentController(UpiPaymentService upi) {
        this.upi = upi;
    }

    @PostMapping("/api/bookings/{bookingId}/upi-paid")
    BookingView claim(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                      @RequestBody(required = false) ClaimRequest req) {
        return upi.claim(bookingId, TokenService.userId(jwt), req == null ? null : req.ref());
    }

    @GetMapping("/api/owner/venues/{venueId}/upi-inbox")
    Inbox inbox(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId) {
        return upi.inbox(TokenService.userId(jwt), venueId);
    }

    @PostMapping("/api/owner/bookings/{bookingId}/upi-received")
    BookingView received(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return upi.markReceived(TokenService.userId(jwt), bookingId);
    }

    @PostMapping("/api/owner/bookings/{bookingId}/upi-not-received")
    BookingView notReceived(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return upi.markNotReceived(TokenService.userId(jwt), bookingId);
    }

    @PostMapping("/api/owner/refunds/{refundId}/sent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void refundSent(@AuthenticationPrincipal Jwt jwt, @PathVariable long refundId) {
        upi.markRefundSent(TokenService.userId(jwt), refundId);
    }
}
