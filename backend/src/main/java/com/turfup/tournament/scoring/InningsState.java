package com.turfup.tournament.scoring;

import java.util.List;
import java.util.UUID;

/**
 * An innings as computed from its balls by {@link InningsScorer}: the scorecard, and while it is being played, who's
 * on and what may happen next. Players are ids; names are added where the API builds its responses.
 *
 * @param wicketsInHand wickets left before all out ("won by N wickets")
 * @param overs         legal balls as overs, e.g. "7.3"
 * @param target        runs to win; null in the first innings
 * @param runsNeeded    null in the first innings; 0 once the target is reached
 * @param endReason     null while the innings is being played
 * @param batting       everyone who has batted, in batting order
 * @param bowling       everyone who has bowled, in the order they came on
 * @param live          null once the innings has ended
 */
public record InningsState(int runs, int wickets, int wicketsInHand, int legalBalls, String overs, Extras extras,
                           Integer target,
                           Integer runsNeeded, int ballsLeft, InningsEndReason endReason, List<BattingLine> batting,
                           List<BowlingLine> bowling, List<FallOfWicket> fallOfWickets, List<BallSummary> balls,
                           Live live) {

    public boolean ended() {
        return endReason != null;
    }

    public enum BatterStatus { BATTING, NOT_OUT, OUT }

    /** How a batter got out; ids for the bowler and fielder, either may be null. */
    public record Dismissal(WicketType type, Long bowlerId, Long fielderId) {
    }

    /** @param balls legal balls and no-balls faced (wides are not) */
    public record BattingLine(long cricketerId, BatterStatus status, Dismissal dismissal, int runs, int balls,
                              int fours, int sixes) {
    }

    /** @param runs off the bat plus wides and no-balls */
    public record BowlingLine(long cricketerId, int legalBalls, String overs, int maidens, int runs, int wickets,
                              int wides, int noBalls) {
    }

    /** Sums of {@code extra_runs} by type. */
    public record Extras(int wides, int noBalls, int byes, int legByes) {

        public int total() {
            return wides + noBalls + byes + legByes;
        }
    }

    /** @param legalBalls when it fell */
    public record FallOfWicket(int wicket, int runs, int legalBalls, String overs, long cricketerId) {
    }

    /**
     * One ball for display.
     *
     * @param over       0-based over it belongs to
     * @param ballInOver 1-based; a wide or no-ball shows the number of the ball still to be bowled again
     * @param label      "0", "4", "W", "1wd", "5wd" (wide + 4 byes), "nb4", "2b", "1lb", "1W" (run out after a run)
     * @param freeHit    it was a free hit
     */
    public record BallSummary(UUID deliveryId, int seq, int over, int ballInOver, String label, int runs, boolean legal,
                              boolean wicket, boolean boundary, boolean freeHit) {
    }

    /**
     * What the scorer screen needs for the next ball. The engine's suggestion of the ends; the scorer may swap them.
     *
     * @param strikerId          null when a new batter is needed at the striker's end
     * @param nonStrikerId       null when a new batter is needed at the non-striker's end
     * @param bowlerId           this over's bowler; before the first ball the opening bowler; null when a new over
     *                           needs a bowler picked from {@code eligibleBowlerIds}
     * @param newOver            the next ball starts an over
     * @param freeHit            the next ball is a free hit
     * @param availableBatterIds who can come in to bat
     */
    public record Live(Long strikerId, Long nonStrikerId, Long bowlerId, Long lastOverBowlerId, boolean newOver,
                       boolean freeHit, int nextSeq, List<Long> eligibleBowlerIds, List<Long> availableBatterIds) {

        public boolean needsNewBatter() {
            return strikerId == null || nonStrikerId == null;
        }
    }
}
