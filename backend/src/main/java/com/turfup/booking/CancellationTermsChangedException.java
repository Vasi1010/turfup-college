package com.turfup.booking;

import com.turfup.booking.CancellationService.CancellationTerms;

/**
 * The refund the player agreed to is no longer what cancelling would give (the deadline passed, or the
 * monthly refund was used meanwhile). Nothing was changed; {@link #getTerms()} has the current terms.
 */
public class CancellationTermsChangedException extends RuntimeException {

    private final CancellationTerms terms;

    public CancellationTermsChangedException(CancellationTerms terms) {
        super("The refund for this cancellation has changed. " + terms.message() + ". Please review and confirm again.");
        this.terms = terms;
    }

    public CancellationTerms getTerms() {
        return terms;
    }
}
