package com.turfup.pricing;

/** The court has no price rule for part of the requested time, so it cannot be booked. */
public class PriceNotConfiguredException extends RuntimeException {

    public PriceNotConfiguredException() {
        super("This court has no price set for the selected time.");
    }
}
