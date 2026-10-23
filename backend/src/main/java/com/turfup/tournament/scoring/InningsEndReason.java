package com.turfup.tournament.scoring;

/** {@code innings.end_reason}. */
public enum InningsEndReason {
    OVERS,
    ALL_OUT,
    /** The chasing side passed the target. */
    TARGET,
    /** Closed early by the scorer, e.g. for rain; never decided by the engine. */
    CLOSED
}
