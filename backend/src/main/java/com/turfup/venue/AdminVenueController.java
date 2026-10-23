package com.turfup.venue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN only (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin/venues")
class AdminVenueController {

    record ApprovalRequest(@NotNull Boolean approved) {
    }

    /** Blank {@code accountId} unlinks. */
    record LinkedAccountRequest(String accountId) {
    }

    private final VenueApprovalService approvals;

    AdminVenueController(VenueApprovalService approvals) {
        this.approvals = approvals;
    }

    /** {@code ?approved=false} is the review queue. */
    @GetMapping
    List<VenueApprovalService.AdminVenue> list(@RequestParam(defaultValue = "false") boolean approved) {
        return approvals.list(approved);
    }

    @PutMapping("/{venueId}/approval")
    VenueView setApproval(@PathVariable long venueId, @Valid @RequestBody ApprovalRequest req) {
        return approvals.setApproved(venueId, req.approved());
    }

    @PutMapping("/{venueId}/linked-account")
    VenueView setLinkedAccount(@PathVariable long venueId, @RequestBody LinkedAccountRequest req) {
        return approvals.setLinkedAccount(venueId, req.accountId());
    }
}
