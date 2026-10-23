package com.turfup.venue;

import com.turfup.payment.LinkedAccountService;
import com.turfup.payment.PaymentsProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin approval of venues. Unapproved venues are hidden from players (no slots, no holds on any court); their
 * owners can still set them up and enter walk-ins. In razorpay mode a venue can be approved only while its owner's
 * Razorpay linked account is activated, and in upi mode only once it has a UPI ID, so every listed venue can take
 * advances into the owner's account.
 */
@Service
public class VenueApprovalService {

    private final JdbcClient jdbc;
    private final VenueSetupService venues;
    private final LinkedAccountService linkedAccounts;
    private final PaymentsProperties payments;

    public VenueApprovalService(JdbcClient jdbc, VenueSetupService venues, LinkedAccountService linkedAccounts,
                                PaymentsProperties payments) {
        this.jdbc = jdbc;
        this.venues = venues;
        this.linkedAccounts = linkedAccounts;
        this.payments = payments;
    }

    /**
     * Approves or unapproves a venue. Approving in razorpay mode first re-checks the linked account with Razorpay,
     * then approves only if it is activated (checked in the same statement that approves).
     *
     * @throws VenueNotFoundException        if the venue does not exist
     * @throws VenueApprovalBlockedException if approving in razorpay mode without an activated linked account
     */
    public VenueView setApproved(long venueId, boolean approved) {
        if (approved && payments.upiMode()) {
            boolean hasUpiId = jdbc.sql("SELECT upi_id IS NOT NULL FROM venues WHERE id = :venueId")
                    .param("venueId", venueId)
                    .query(Boolean.class)
                    .optional()
                    .orElseThrow(() -> new VenueNotFoundException(venueId));
            if (!hasUpiId) {
                throw VenueApprovalBlockedException.upiIdMissing();
            }
        }
        boolean gated = approved && payments.razorpayMode();
        if (gated) {
            linkedAccounts.refresh(venueId);
        }
        int updated = jdbc.sql("""
                        UPDATE venues SET approved = :approved
                        WHERE id = :venueId AND (NOT :gated OR razorpay_account_status = :activated)
                        """)
                .param("approved", approved)
                .param("venueId", venueId)
                .param("gated", gated)
                .param("activated", LinkedAccountService.ACTIVATED)
                .update();
        if (updated == 0) {
            record Account(String razorpayAccountStatus) {
            }
            Account account = jdbc.sql("SELECT razorpay_account_status FROM venues WHERE id = :venueId")
                    .param("venueId", venueId)
                    .query(Account.class)
                    .optional()
                    .orElseThrow(() -> new VenueNotFoundException(venueId));
            throw new VenueApprovalBlockedException(account.razorpayAccountStatus());
        }
        return venues.find(venueId);
    }

    /**
     * Admin sets (or, with a blank id, clears) the Razorpay linked account that receives this venue's advances
     * (for every court).
     *
     * @throws IllegalArgumentException if the id is malformed or unknown to Razorpay
     * @throws VenueNotFoundException   if the venue does not exist
     */
    public VenueView setLinkedAccount(long venueId, String accountId) {
        linkedAccounts.link(venueId, accountId);
        return venues.find(venueId);
    }

    /** A venue in the admin review list, with who owns it. */
    public record AdminVenue(VenueView venue, String ownerName, String ownerPhone) {
    }

    /** All venues with the given approval state, oldest first (the review queue when {@code approved = false}). */
    @Transactional(readOnly = true)
    public List<AdminVenue> list(boolean approved) {
        record Row(long id, String ownerName, String ownerPhone) {
        }
        return jdbc.sql("""
                        SELECT v.id, u.name AS owner_name, u.phone AS owner_phone
                        FROM venues v JOIN users u ON u.id = v.owner_id
                        WHERE v.approved = :approved ORDER BY v.id
                        """)
                .param("approved", approved)
                .query(Row.class)
                .list()
                .stream()
                .map(r -> new AdminVenue(venues.find(r.id()), r.ownerName(), r.ownerPhone()))
                .toList();
    }
}
