package com.turfup.tournament;

import com.turfup.tournament.scoring.ScoringRules;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/matches/{id}}, and the response of every scorer call: the shape of {@code MatchDetail} in
 * frontend/src/lib/tournamentTypes.ts. Every number is computed from the balls ({@code InningsScorer}).
 *
 * @param rules   the rules this match is scored by (the tournament's, with the match's own overs)
 * @param version +1 on every scoring change: a page polling every 5 s redraws only when it changed
 */
public record MatchView(long id, long tournamentId, int matchNo, String stage, OffsetDateTime scheduledAt,
                        String status, int oversPerInnings, ScoringRules rules, Side teamA, Side teamB, Toss toss,
                        List<InningsCard> innings, LiveState live, String result, Long winnerId, String resultText,
                        String resultNote, int version) {

    /**
     * A team in the fixture: the team once known, otherwise a label such as "Winner SF 1".
     *
     * @param squad the team's squad in squad order (empty until the team is known), for picking players
     */
    public record Side(Long teamId, String label, String name, String shortName, List<Named> squad) {
    }

    public record Toss(long winnerId, String decision) {
    }

    /**
     * @param status    LIVE or DONE
     * @param endReason null while LIVE
     * @param target         runs to win; null in the first innings
     * @param lastDeliveryId the latest ball, for undo (also once the innings is over); null before the first ball
     */
    public record InningsCard(long id, int number, long battingTeamId, long bowlingTeamId, String status, int runs,
                              int wickets, int legalBalls, String overs, String endReason, Extras extras, Integer target,
                              List<BattingLine> batting, List<Named> yetToBat, List<BowlingLine> bowling,
                              List<FallOfWicket> fallOfWickets, UUID lastDeliveryId) {
    }

    /**
     * @param status    BATTING, NOT_OUT or OUT
     * @param dismissal e.g. "c Khan b Patil", "run out (Pawar)"; null unless OUT
     * @param balls     legal balls and no-balls faced
     */
    public record BattingLine(long cricketerId, String name, String status, String dismissal, int runs, int balls,
                              int fours, int sixes, BigDecimal strikeRate, boolean onStrike) {
    }

    /** @param bowling bowling the current over */
    public record BowlingLine(long cricketerId, String name, int legalBalls, String overs, int maidens, int runs,
                              int wickets, int wides, int noBalls, BigDecimal economy, boolean bowling) {
    }

    public record Extras(int wides, int noBalls, int byes, int legByes, int total) {
    }

    public record FallOfWicket(int wicket, int runs, String overs, long cricketerId, String name) {
    }

    public record Named(long cricketerId, String name) {
    }

    /** One ball: label "0", "4", "W", "1wd", "5wd", "nb4", "nb+2", "2b", "1lb", "1W". */
    public record BallSummary(UUID deliveryId, String label, int runs, boolean legal, boolean wicket,
                              boolean boundary, boolean freeHit) {
    }

    /**
     * The innings being played, for the live page and the scorer screen. The ends are the engine's suggestion; the
     * scorer may swap them.
     *
     * @param strikerId          null when a new batter is needed at the striker's end
     * @param nonStrikerId       null when a new batter is needed at the non-striker's end
     * @param bowlerId           this over's bowler (before the first ball, the opening bowler); null when a new over
     *                           needs a bowler from {@code eligibleBowlerIds}
     * @param nextSeq            the next ball's seq, for {@code POST /api/innings/{id}/deliveries}
     * @param thisOver           the current over so far, wides and no-balls included
     * @param recentBalls        the last 12 balls, oldest first
     * @param runsNeeded         second innings only
     * @param requiredRunRate    second innings only, and null with no balls left
     * @param availableBatterIds who can come in to bat
     */
    public record LiveState(long inningsId, int inningsNumber, Long strikerId, Long nonStrikerId, Long bowlerId,
                            Long lastOverBowlerId, int nextSeq, List<BallSummary> thisOver,
                            List<BallSummary> recentBalls, BigDecimal currentRunRate, Integer runsNeeded,
                            int ballsLeft, BigDecimal requiredRunRate, List<Long> eligibleBowlerIds,
                            List<Long> availableBatterIds, boolean newOver, boolean needsNewBatter, boolean freeHit) {
    }
}
