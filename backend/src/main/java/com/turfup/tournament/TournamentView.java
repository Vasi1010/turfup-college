package com.turfup.tournament;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The public tournament responses, in the shapes of frontend/src/lib/tournamentTypes.ts: the tournament with its
 * fixtures ({@link Detail}), the points table ({@link Table}) and the leaderboards ({@link Leaders}). Every figure
 * is computed from the balls.
 */
public final class TournamentView {

    private TournamentView() {
    }

    /**
     * {@code GET /api/tournaments/{id}}.
     *
     * @param status UPCOMING until a match starts, LIVE while matches are being played, DONE once every match is
     *               finished or abandoned
     */
    public record Detail(long id, String name, String venueName, LocalDate startDate, LocalDate endDate,
                         String status, Rules rules, List<Team> teams, List<Fixture> matches) {
    }

    /** @param maxOversPerBowler null: no limit */
    public record Rules(int oversPerInnings, Integer maxOversPerBowler, int ballsPerOver, int playersPerSide,
                        int wideRuns, int noBallRuns, int pointsWin, int pointsTie, int pointsNoResult,
                        String tiebreaker, LocalRules localRules) {
    }

    public record LocalRules(boolean noLbw, boolean freeHit, boolean lastManStands) {
    }

    public record Team(long id, String name, String shortName, String groupName, Long captainId,
                       List<Cricketer> squad) {
    }

    public record Cricketer(long id, String name, Integer jerseyNo) {
    }

    /** A match in the fixture list; its played innings in order. */
    public record Fixture(long id, int matchNo, String stage, OffsetDateTime scheduledAt, String status,
                          Side teamA, Side teamB, List<InningsScore> innings, String result, Long winnerId,
                          String resultText) {
    }

    /** The team once known, otherwise a label such as "Winner SF 1". */
    public record Side(Long teamId, String label) {
    }

    public record InningsScore(int number, long battingTeamId, int runs, int wickets, int legalBalls, String overs,
                               String endReason) {
    }

    /** {@code GET /api/tournaments/{id}/table}: finished group matches; one group named null without groups. */
    public record Table(List<Group> groups) {
    }

    public record Group(String name, List<PointsRow> rows) {
    }

    /** @param nrr 3 decimals; a side bowled out counts as having faced its full overs */
    public record PointsRow(int position, long teamId, String teamName, String shortName, int played, int won,
                            int lost, int tied, int noResult, int points, int runsFor, int ballsFaced,
                            String oversFaced, int runsAgainst, int ballsBowled, String oversBowled,
                            BigDecimal nrr) {
    }

    /** {@code GET /api/tournaments/{id}/leaderboards}: the top 10 of each, live matches included. */
    public record Leaders(List<RunScorer> topRunScorers, List<WicketTaker> topWicketTakers) {
    }

    /** @param highest "34*" when not out */
    public record RunScorer(long cricketerId, String name, long teamId, String teamShortName, int innings,
                            int notOuts, int runs, int balls, int fours, int sixes, String highest,
                            BigDecimal strikeRate) {
    }

    /** @param best best figures in one innings, e.g. "3/12" */
    public record WicketTaker(long cricketerId, String name, long teamId, String teamShortName, int innings,
                              int legalBalls, String overs, int runs, int wickets, BigDecimal economy, String best) {
    }
}
