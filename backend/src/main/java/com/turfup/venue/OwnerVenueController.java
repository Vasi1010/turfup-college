package com.turfup.venue;

import com.turfup.auth.TokenService;
import com.turfup.pricing.PriceRule;
import com.turfup.venue.VenueCustomerService.VenueCustomerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Owner: their venues, courts and regulars. */
@RestController
@RequestMapping("/api/owner")
class OwnerVenueController {

    static final int DEFAULT_REFUND_FULL_HOURS_BEFORE = 24;
    static final int DEFAULT_REFUNDS_PER_MONTH = 1;

    record VenueRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 120) String area,
            @NotBlank String address,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
            @NotNull Integer openMinute,
            @NotNull Integer closeMinute,
            Integer refundsPerMonth) {

        VenueSetup toSetup() {
            return new VenueSetup(name, area, address, lat, lng, openMinute, closeMinute,
                    refundsPerMonth == null ? DEFAULT_REFUNDS_PER_MONTH : refundsPerMonth);
        }
    }

    record CourtRequest(
            @NotBlank @Size(max = 60) String name,
            @NotEmpty List<@NotBlank String> sports,
            @NotNull Integer slotMinutes,
            @NotNull Long advanceAmount,
            Integer refundFullHoursBefore,
            String refundPolicy,
            Boolean active,
            @NotNull List<@NotNull PriceRule> priceRules) {

        CourtSetup toSetup() {
            return new CourtSetup(name, sports, slotMinutes, advanceAmount,
                    refundFullHoursBefore == null ? DEFAULT_REFUND_FULL_HOURS_BEFORE : refundFullHoursBefore,
                    refundPolicy, active == null || active, priceRules);
        }
    }

    /** A new venue and its first court(s). */
    record NewVenueRequest(@NotNull @Valid VenueRequest venue, @NotEmpty List<@NotNull @Valid CourtRequest> courts) {
    }

    record WaiverRequest(@NotNull Boolean advanceWaived) {
    }

    /** A blank {@code upiId} clears it. */
    record UpiRequest(@Size(max = 100) String upiId, @Size(max = 60) String upiName) {
    }

    private final VenueSetupService setup;
    private final VenueCustomerService customers;

    OwnerVenueController(VenueSetupService setup, VenueCustomerService customers) {
        this.setup = setup;
        this.customers = customers;
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    VenueView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewVenueRequest req) {
        return setup.create(TokenService.userId(jwt), req.venue().toSetup(),
                req.courts().stream().map(CourtRequest::toSetup).toList());
    }

    @PutMapping("/venues/{venueId}")
    VenueView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId, @Valid @RequestBody VenueRequest req) {
        return setup.update(TokenService.userId(jwt), venueId, req.toSetup());
    }

    @GetMapping("/venues")
    List<VenueView> mine(@AuthenticationPrincipal Jwt jwt) {
        return setup.listForOwner(TokenService.userId(jwt));
    }

    @PostMapping("/venues/{venueId}/courts")
    @ResponseStatus(HttpStatus.CREATED)
    VenueView addCourt(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId,
                       @Valid @RequestBody CourtRequest req) {
        return setup.addCourt(TokenService.userId(jwt), venueId, req.toSetup());
    }

    @PutMapping("/courts/{courtId}")
    VenueView updateCourt(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                          @Valid @RequestBody CourtRequest req) {
        return setup.updateCourt(TokenService.userId(jwt), courtId, req.toSetup());
    }

    @GetMapping("/venues/{venueId}/customers")
    VenueCustomerService.Regulars customers(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId) {
        return customers.list(TokenService.userId(jwt), venueId);
    }

    /** The UPI ID this venue's advances are paid to (upi mode). */
    @PutMapping("/venues/{venueId}/upi")
    VenueView upi(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId, @Valid @RequestBody UpiRequest req) {
        return setup.setUpi(TokenService.userId(jwt), venueId, req.upiId(), req.upiName());
    }

    /** One tap to grant or revoke a regular's advance waiver at this venue (every court). */
    @PutMapping("/venues/{venueId}/customers/{playerId}/waiver")
    VenueCustomerView waiver(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId, @PathVariable long playerId,
                             @Valid @RequestBody WaiverRequest req) {
        return customers.setAdvanceWaived(TokenService.userId(jwt), venueId, playerId, req.advanceWaived());
    }
}
