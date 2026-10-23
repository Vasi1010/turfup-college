package com.turfup.venue;

import com.turfup.pricing.PriceRule;

import java.util.List;

/**
 * What an owner sets on one court: its sports (codes from table {@code sports}; a shared pitch has several),
 * slot length, advance (paise), player refund notice and policy text, and its price rules (paise per hour,
 * operating-day minutes). Saving replaces all of the court's price rules. An inactive court is paused: hidden
 * from players, still open to the owner's own entries.
 */
public record CourtSetup(
        String name,
        List<String> sports,
        int slotMinutes,
        long advanceAmount,
        int refundFullHoursBefore,
        String refundPolicy,
        boolean active,
        List<PriceRule> priceRules) {
}
