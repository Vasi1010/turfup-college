package com.turfup.tournament.scoring;

/** {@code deliveries.wicket_type}. */
public enum WicketType {
    BOWLED,
    CAUGHT,
    LBW,
    RUN_OUT,
    STUMPED,
    HIT_WICKET,
    /** Not scored yet: a retirement happens between balls, and every row here is a ball. */
    RETIRED_OUT,
    /** Not scored yet, as {@link #RETIRED_OUT}. Not a wicket. */
    RETIRED_HURT;

    /** Counts in the bowler's wickets. */
    public boolean creditedToBowler() {
        return this == BOWLED || this == CAUGHT || this == LBW || this == STUMPED || this == HIT_WICKET;
    }

    /** Counts towards the innings' wickets. */
    public boolean countsAsWicket() {
        return this != RETIRED_HURT;
    }

    /** A fielder may be named. */
    public boolean hasFielder() {
        return this == CAUGHT || this == RUN_OUT || this == STUMPED;
    }

    /** The batters may have completed runs before it (only a run out). */
    public boolean allowsRuns() {
        return this == RUN_OUT;
    }
}
