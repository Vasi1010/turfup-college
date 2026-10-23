package com.turfup.venue;

import java.math.BigDecimal;

/**
 * What an owner sets on a venue: the place and its opening hours (operating-day minutes), shared by every court.
 * {@code refundsPerMonth} is the player cancellation refund allowance per player per venue per IST month.
 */
public record VenueSetup(
        String name,
        String area,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        int openMinute,
        int closeMinute,
        int refundsPerMonth) {
}
