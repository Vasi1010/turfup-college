package com.turfup.venue;

import com.turfup.pricing.PriceRule;

import java.util.List;

/** A court as its owner (or an admin) sees it. Money is paise. */
public record CourtView(
        long id,
        long venueId,
        String name,
        List<String> sports,
        int slotMinutes,
        long advanceAmount,
        int refundFullHoursBefore,
        String refundPolicy,
        boolean active,
        int sortOrder,
        List<PriceRule> priceRules) {
}
