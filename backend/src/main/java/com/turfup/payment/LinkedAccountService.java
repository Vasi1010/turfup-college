package com.turfup.payment;

import com.turfup.venue.VenueNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Razorpay Route linked accounts: where each venue's advances go. Only an admin sets the account id. In razorpay
 * mode a venue needs an {@code activated} account to be approved, and to take online advances afterwards; in
 * simulated mode none of this applies.
 */
@Service
public class LinkedAccountService {

    public static final String ACTIVATED = "activated";

    private static final Logger log = LoggerFactory.getLogger(LinkedAccountService.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("acc_[A-Za-z0-9]{8,24}");

    private final JdbcClient jdbc;
    private final RazorpayGateway gateway;
    private final PaymentsProperties props;

    public LinkedAccountService(JdbcClient jdbc, RazorpayGateway gateway, PaymentsProperties props) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.props = props;
    }

    /**
     * True if players can pay a venue's advance online: in razorpay mode its linked account is activated, in upi mode
     * it has a UPI ID, in simulated mode always.
     */
    public boolean acceptsOnlineAdvance(String accountStatus, String upiId) {
        return switch (props.mode()) {
            case SIMULATED -> true;
            case UPI -> upiId != null;
            case RAZORPAY -> ACTIVATED.equals(accountStatus);
        };
    }

    /** The same rule for a venue, read inside the caller's transaction. */
    public boolean acceptsOnlineAdvance(long venueId) {
        record Venue(String razorpayAccountStatus, String upiId) {
        }
        return jdbc.sql("SELECT razorpay_account_status, upi_id FROM venues WHERE id = :id")
                .param("id", venueId)
                .query(Venue.class)
                .optional()
                .map(v -> acceptsOnlineAdvance(v.razorpayAccountStatus(), v.upiId()))
                .orElse(false);
    }

    /**
     * Admin links a venue to its owner's Razorpay account, after checking with Razorpay that the account exists.
     * A blank id unlinks it (online advances then stop in razorpay mode).
     *
     * @return the account's status at Razorpay, or null when unlinked
     * @throws IllegalArgumentException if the id is malformed or Razorpay has no such account
     * @throws VenueNotFoundException    if the venue does not exist
     */
    public String link(long venueId, String accountId) {
        String id = accountId == null ? "" : accountId.strip();
        String status = null;
        if (!id.isEmpty()) {
            if (!ACCOUNT_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("A Razorpay linked account id looks like acc_XXXXXXXXXXXXXX.");
            }
            try {
                status = gateway.accountStatus(id);
            } catch (RazorpayException e) {
                if (e.notFound()) {
                    throw new IllegalArgumentException("Razorpay has no linked account " + id + ".");
                }
                throw e;
            }
        }
        int updated = id.isEmpty()
                ? jdbc.sql("""
                                UPDATE venues SET razorpay_account_id = NULL, razorpay_account_status = NULL,
                                                 razorpay_account_checked_at = NULL
                                WHERE id = :venueId
                                """)
                        .param("venueId", venueId)
                        .update()
                : jdbc.sql("""
                                UPDATE venues SET razorpay_account_id = :id, razorpay_account_status = :status,
                                                 razorpay_account_checked_at = now()
                                WHERE id = :venueId
                                """)
                        .param("id", id)
                        .param("status", status)
                        .param("venueId", venueId)
                        .update();
        if (updated == 0) {
            throw new VenueNotFoundException(venueId);
        }
        return status;
    }

    /** Fetches the venue's account status from Razorpay and stores it. Failures keep the last known status. */
    public void refresh(long venueId) {
        String accountId = jdbc.sql("SELECT razorpay_account_id FROM venues WHERE id = :id")
                .param("id", venueId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (accountId == null || !props.razorpayMode()) {
            return;
        }
        try {
            String status = gateway.accountStatus(accountId);
            jdbc.sql("""
                            UPDATE venues SET razorpay_account_status = :status, razorpay_account_checked_at = now()
                            WHERE id = :venueId AND razorpay_account_id = :accountId
                            """)
                    .param("status", status)
                    .param("venueId", venueId)
                    .param("accountId", accountId)
                    .update();
            if (!ACTIVATED.equals(status)) {
                log.warn("Venue {} linked account {} is {}: online advances are paused", venueId, accountId, status);
            }
        } catch (RazorpayException e) {
            log.warn("Could not refresh linked account {} of venue {}: {}", accountId, venueId, e.getMessage());
        }
    }

    /** Refreshes every linked account (hourly job). */
    public void refreshAll() {
        if (!props.razorpayMode()) {
            return;
        }
        List<Long> venueIds = jdbc.sql("SELECT id FROM venues WHERE razorpay_account_id IS NOT NULL ORDER BY id")
                .query(Long.class)
                .list();
        venueIds.forEach(this::refresh);
    }
}
