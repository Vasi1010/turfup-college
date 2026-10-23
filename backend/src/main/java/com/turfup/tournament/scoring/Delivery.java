package com.turfup.tournament.scoring;

import java.util.UUID;

/**
 * One ball as recorded ({@code deliveries} row). The scorer names the batters and the bowler on every ball, so the
 * rows stay right even when the engine's suggestion of who's on strike was wrong.
 *
 * @param runsBat    off the bat, also on a no-ball
 * @param extraType  null for a ball with no extras
 * @param extraRuns  every extra on the ball, penalty included: a wide with 4 byes is 5
 * @param playerOutId null unless there is a {@code wicketType}
 * @param fielderId  optional; only for caught, run out and stumped
 */
public record Delivery(UUID id, int seq, long strikerId, long nonStrikerId, long bowlerId, int runsBat,
                       ExtraType extraType, int extraRuns, WicketType wicketType, Long playerOutId, Long fielderId) {

    public boolean legal() {
        return extraType == null || extraType.legal();
    }

    public int totalRuns() {
        return runsBat + extraRuns;
    }

    /** Runs charged to the bowler: off the bat, plus wides and no-balls. Byes and leg byes are not the bowler's. */
    public int bowlerRuns() {
        boolean bowlersExtras = extraType == ExtraType.WD || extraType == ExtraType.NB;
        return runsBat + (bowlersExtras ? extraRuns : 0);
    }

    /** Runs the batters ran or hit (not penalties): an odd number means they swapped ends. */
    public int runsCompleted(ScoringRules rules) {
        return runsBat + extraRuns - (extraType == null ? 0 : extraType.penalty(rules));
    }
}
