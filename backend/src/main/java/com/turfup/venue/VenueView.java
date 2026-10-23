package com.turfup.venue;

import java.math.BigDecimal;
import java.util.List;

/**
 * A venue as its owner (or an admin) sees it, with its courts in display order. {@code onlinePayments} is false
 * while a razorpay-mode venue cannot take online advances (no active linked account); always true in simulated
 * mode.
 */
public record VenueView(
        long id,
        String name,
        String area,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        int openMinute,
        int closeMinute,
        int refundsPerMonth,
        boolean active,
        boolean approved,
        String razorpayAccountId,
        String razorpayAccountStatus,
        String upiId,
        String upiName,
        boolean onlinePayments,
        List<CourtView> courts) {
}
