package com.turfup.web;

import com.turfup.auth.InvalidCredentialsException;
import com.turfup.auth.PhoneAlreadyRegisteredException;
import com.turfup.auth.PlayerNotFoundException;
import com.turfup.booking.ActiveHoldExistsException;
import com.turfup.booking.BookingNotFoundException;
import com.turfup.booking.CancellationTermsChangedException;
import com.turfup.booking.InvalidBookingStateException;
import com.turfup.booking.InvalidRecurringException;
import com.turfup.booking.RecurringNotFoundException;
import com.turfup.booking.PaymentWindowClosedException;
import com.turfup.booking.SlotUnavailableException;
import com.turfup.payment.InvalidWebhookSignatureException;
import com.turfup.payment.OnlinePaymentUnavailableException;
import com.turfup.payment.RazorpayException;
import com.turfup.payment.RefundNotOpenException;
import com.turfup.payment.UnknownPaymentOrderException;
import com.turfup.pricing.PriceNotConfiguredException;
import com.turfup.tournament.MatchNotFoundException;
import com.turfup.tournament.NotMatchScorerException;
import com.turfup.tournament.ScoreConflictException;
import com.turfup.tournament.scoring.InvalidDeliveryException;
import com.turfup.venue.CourtNotFoundException;
import com.turfup.venue.InvalidSlotException;
import com.turfup.venue.InvalidVenueSetupException;
import com.turfup.venue.VenueApprovalBlockedException;
import com.turfup.venue.VenueNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Locale;
import java.util.Map;

/**
 * Maps domain exceptions to RFC 9457 problem responses. {@code detail} is a message safe to show the
 * user; {@code code} is stable for the frontend to branch on.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<Class<? extends RuntimeException>, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(VenueNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(CourtNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(BookingNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(RecurringNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(InvalidRecurringException.class, HttpStatus.BAD_REQUEST),
            Map.entry(PlayerNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(UnknownPaymentOrderException.class, HttpStatus.NOT_FOUND),
            Map.entry(SlotUnavailableException.class, HttpStatus.CONFLICT),
            Map.entry(ActiveHoldExistsException.class, HttpStatus.CONFLICT),
            Map.entry(PhoneAlreadyRegisteredException.class, HttpStatus.CONFLICT),
            Map.entry(InvalidBookingStateException.class, HttpStatus.CONFLICT),
            Map.entry(PaymentWindowClosedException.class, HttpStatus.CONFLICT),
            Map.entry(CancellationTermsChangedException.class, HttpStatus.CONFLICT),
            Map.entry(OnlinePaymentUnavailableException.class, HttpStatus.CONFLICT),
            Map.entry(VenueApprovalBlockedException.class, HttpStatus.CONFLICT),
            Map.entry(RefundNotOpenException.class, HttpStatus.CONFLICT),
            Map.entry(InvalidWebhookSignatureException.class, HttpStatus.UNAUTHORIZED),
            Map.entry(RazorpayException.class, HttpStatus.BAD_GATEWAY),
            Map.entry(InvalidSlotException.class, HttpStatus.BAD_REQUEST),
            Map.entry(PriceNotConfiguredException.class, HttpStatus.BAD_REQUEST),
            Map.entry(InvalidVenueSetupException.class, HttpStatus.BAD_REQUEST),
            Map.entry(IllegalArgumentException.class, HttpStatus.BAD_REQUEST),
            Map.entry(InvalidCredentialsException.class, HttpStatus.UNAUTHORIZED),
            Map.entry(MatchNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(NotMatchScorerException.class, HttpStatus.FORBIDDEN),
            Map.entry(ScoreConflictException.class, HttpStatus.CONFLICT),
            Map.entry(InvalidDeliveryException.class, HttpStatus.BAD_REQUEST),
            Map.entry(UnsupportedOperationException.class, HttpStatus.NOT_IMPLEMENTED));

    @ExceptionHandler({
            VenueNotFoundException.class, CourtNotFoundException.class, BookingNotFoundException.class, PlayerNotFoundException.class,
            RecurringNotFoundException.class, InvalidRecurringException.class,
            UnknownPaymentOrderException.class, SlotUnavailableException.class, ActiveHoldExistsException.class,
            PhoneAlreadyRegisteredException.class, InvalidBookingStateException.class,
            PaymentWindowClosedException.class, CancellationTermsChangedException.class, InvalidSlotException.class, PriceNotConfiguredException.class,
            InvalidVenueSetupException.class, IllegalArgumentException.class, InvalidCredentialsException.class,
            UnsupportedOperationException.class, OnlinePaymentUnavailableException.class,
            VenueApprovalBlockedException.class, InvalidWebhookSignatureException.class, RazorpayException.class,
            RefundNotOpenException.class, MatchNotFoundException.class, NotMatchScorerException.class,
            ScoreConflictException.class, InvalidDeliveryException.class})
    ProblemDetail handle(RuntimeException e) {
        HttpStatus status = STATUS.getOrDefault(e.getClass(), HttpStatus.BAD_REQUEST);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", code(e));
        if (e instanceof InvalidSlotException invalid) {
            problem.setProperty("reason", invalid.getReason().name());
        }
        if (e instanceof CancellationTermsChangedException changed) {
            problem.setProperty("terms", changed.getTerms());
        }
        if (e instanceof ScoreConflictException conflict) {
            problem.setProperty("match", conflict.getMatch());
        }
        return problem;
    }

    /** {@code SlotUnavailableException} -> {@code SLOT_UNAVAILABLE}. */
    private static String code(RuntimeException e) {
        String name = e.getClass().getSimpleName().replaceFirst("Exception$", "");
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
