package com.turfup.tournament.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The points table of a group, from its finished matches.
 *
 * <p>Net run rate = runs scored ÷ overs faced − runs conceded ÷ overs bowled, over matches with a result (a no result
 * counts for points only). A side bowled out counts as having faced its full overs. Order: points, then the
 * tiebreaker (NRR; or head-to-head points among the tied teams, then NRR), then the order the teams were given in.
 */
public final class Standings {

    private Standings() {
    }

    public enum Result { WIN, TIE, NO_RESULT }

    public enum Tiebreaker { NRR, HEAD_TO_HEAD }

    public record PointsRules(int win, int tie, int noResult, Tiebreaker tiebreaker, int ballsPerOver) {
    }

    /** @param allOut the side was bowled out (counts as its full overs for NRR) */
    public record InningsTotal(long battingTeamId, int runs, int legalBalls, boolean allOut) {
    }

    /**
     * @param winnerId      for WIN; null otherwise
     * @param maxLegalBalls the match's full overs in balls (a shortened match has fewer)
     */
    public record FinishedMatch(long teamAId, long teamBId, Result result, Long winnerId, int maxLegalBalls,
                                List<InningsTotal> innings) {

        public FinishedMatch {
            innings = List.copyOf(innings);
            if (result == Result.WIN && (winnerId == null || (winnerId != teamAId && winnerId != teamBId))) {
                throw new IllegalArgumentException("A win needs a winner from the two teams.");
            }
        }
    }

    /** @param nrr 3 decimals */
    public record Row(long teamId, int played, int won, int lost, int tied, int noResult, int points, int runsFor,
                      int ballsFaced, int runsAgainst, int ballsBowled, BigDecimal nrr) {
    }

    private static final class Tally {
        final long teamId;
        int played;
        int won;
        int lost;
        int tied;
        int noResult;
        int points;
        int runsFor;
        int ballsFaced;
        int runsAgainst;
        int ballsBowled;

        Tally(long teamId) {
            this.teamId = teamId;
        }
    }

    /**
     * @param teamIds the group's teams; ties that nothing separates keep this order (pass them sorted by name)
     */
    public static List<Row> compute(List<Long> teamIds, List<FinishedMatch> matches, PointsRules rules) {
        Map<Long, Tally> tallies = new LinkedHashMap<>();
        teamIds.forEach(id -> tallies.put(id, new Tally(id)));
        for (FinishedMatch m : matches) {
            Tally a = tallies.get(m.teamAId());
            Tally b = tallies.get(m.teamBId());
            if (a == null || b == null) {
                throw new IllegalArgumentException("A match between teams outside the group.");
            }
            for (Tally t : List.of(a, b)) {
                t.played++;
                switch (m.result()) {
                    case WIN -> {
                        if (m.winnerId() == t.teamId) {
                            t.won++;
                            t.points += rules.win();
                        } else {
                            t.lost++;
                        }
                    }
                    case TIE -> {
                        t.tied++;
                        t.points += rules.tie();
                    }
                    case NO_RESULT -> {
                        t.noResult++;
                        t.points += rules.noResult();
                    }
                }
            }
            if (m.result() == Result.NO_RESULT) {
                continue;
            }
            for (InningsTotal inn : m.innings()) {
                Tally batting = tallies.get(inn.battingTeamId());
                Tally bowling = batting == a ? b : a;
                int balls = inn.allOut() ? m.maxLegalBalls() : inn.legalBalls();
                batting.runsFor += inn.runs();
                batting.ballsFaced += balls;
                bowling.runsAgainst += inn.runs();
                bowling.ballsBowled += balls;
            }
        }

        Map<Long, BigDecimal> nrr = new HashMap<>();
        tallies.values().forEach(t -> nrr.put(t.teamId, nrr(t, rules.ballsPerOver())));
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < teamIds.size(); i++) {
            order.put(teamIds.get(i), i);
        }
        Map<Long, Integer> headToHead = rules.tiebreaker() == Tiebreaker.HEAD_TO_HEAD
                ? headToHeadPoints(tallies.values(), matches, rules)
                : Map.of();

        List<Tally> sorted = new ArrayList<>(tallies.values());
        sorted.sort(Comparator.<Tally>comparingInt(t -> -t.points)
                .thenComparingInt(t -> -headToHead.getOrDefault(t.teamId, 0))
                .thenComparing(t -> nrr.get(t.teamId), Comparator.reverseOrder())
                .thenComparingInt(t -> order.get(t.teamId)));
        return sorted.stream().map(t -> new Row(t.teamId, t.played, t.won, t.lost, t.tied, t.noResult, t.points,
                t.runsFor, t.ballsFaced, t.runsAgainst, t.ballsBowled, nrr.get(t.teamId))).toList();
    }

    private static BigDecimal nrr(Tally t, int ballsPerOver) {
        if (t.ballsFaced == 0 || t.ballsBowled == 0) {
            return BigDecimal.ZERO.setScale(3);
        }
        BigDecimal scored = BigDecimal.valueOf((long) t.runsFor * ballsPerOver)
                .divide(BigDecimal.valueOf(t.ballsFaced), 12, RoundingMode.HALF_UP);
        BigDecimal conceded = BigDecimal.valueOf((long) t.runsAgainst * ballsPerOver)
                .divide(BigDecimal.valueOf(t.ballsBowled), 12, RoundingMode.HALF_UP);
        return scored.subtract(conceded).setScale(3, RoundingMode.HALF_UP);
    }

    /** Points each team earned in matches against the teams level with it on points. */
    private static Map<Long, Integer> headToHeadPoints(Iterable<Tally> tallies, List<FinishedMatch> matches,
                                                       PointsRules rules) {
        Map<Integer, Set<Long>> byPoints = new HashMap<>();
        tallies.forEach(t -> byPoints.computeIfAbsent(t.points, p -> new HashSet<>()).add(t.teamId));
        Map<Long, Integer> h2h = new HashMap<>();
        for (Set<Long> level : byPoints.values()) {
            if (level.size() < 2) {
                continue;
            }
            for (FinishedMatch m : matches) {
                if (!level.contains(m.teamAId()) || !level.contains(m.teamBId())) {
                    continue;
                }
                for (long id : new long[] {m.teamAId(), m.teamBId()}) {
                    int p = switch (m.result()) {
                        case WIN -> m.winnerId() == id ? rules.win() : 0;
                        case TIE -> rules.tie();
                        case NO_RESULT -> rules.noResult();
                    };
                    h2h.merge(id, p, Integer::sum);
                }
            }
        }
        return h2h;
    }
}
