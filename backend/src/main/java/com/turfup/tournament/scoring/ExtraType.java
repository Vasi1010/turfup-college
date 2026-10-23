package com.turfup.tournament.scoring;

/** {@code deliveries.extra_type}. */
public enum ExtraType {
    /** Wide: not a legal ball; its runs are all extras and all the bowler's. */
    WD,
    /** No-ball: not a legal ball; runs off the bat are the batter's, the penalty is the bowler's. */
    NB,
    /** Bye: a legal ball; the runs are extras and not the bowler's. */
    B,
    /** Leg bye: as a bye. */
    LB;

    public boolean legal() {
        return this == B || this == LB;
    }

    /** The penalty included in {@code extra_runs}, which the batters did not run. */
    public int penalty(ScoringRules rules) {
        return switch (this) {
            case WD -> rules.wideRuns();
            case NB -> rules.noBallRuns();
            case B, LB -> 0;
        };
    }
}
