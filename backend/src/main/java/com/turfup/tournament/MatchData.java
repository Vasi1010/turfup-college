package com.turfup.tournament;

import com.turfup.tournament.scoring.Delivery;
import com.turfup.tournament.scoring.InningsScorer;
import com.turfup.tournament.scoring.InningsSetup;
import com.turfup.tournament.scoring.InningsState;
import com.turfup.tournament.scoring.ScoringRules;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A match as stored, with everything scoring needs: its rules (the tournament's, with the match's own overs), the two
 * teams and their squads, and each innings with its live balls in {@code seq} order. Loaded by {@link MatchLoader}.
 *
 * @param squads cricketer ids by team, in squad order
 * @param names  cricketer names by id
 */
record MatchData(long id, long tournamentId, int matchNo, String stage, OffsetDateTime scheduledAt, String status,
                 Long teamAId, Long teamBId, String teamALabel, String teamBLabel, Long tossWinnerId,
                 String tossDecision, String result, Long winnerId, String resultNote, Long scorerUserId, int version,
                 ScoringRules rules, boolean lastManStands, Map<Long, Team> teams, Map<Long, List<Long>> squads,
                 Map<Long, String> names, List<Innings> innings) {

    record Team(long id, String name, String shortName) {
    }

    record Innings(long id, int number, long battingTeamId, long bowlingTeamId, long openingStrikerId,
                   long openingNonStrikerId, long openingBowlerId, String status, String endReason,
                   List<Delivery> deliveries) {

        boolean live() {
            return status.equals("LIVE");
        }
    }

    Optional<Innings> innings(long inningsId) {
        return innings.stream().filter(i -> i.id() == inningsId).findFirst();
    }

    long otherTeam(long teamId) {
        return teamId == teamAId ? teamBId : teamAId;
    }

    List<Long> squad(long teamId) {
        return squads.getOrDefault(teamId, List.of());
    }

    InningsSetup setup(Innings inn, Integer target) {
        return new InningsSetup(squad(inn.battingTeamId()), squad(inn.bowlingTeamId()), inn.openingStrikerId(),
                inn.openingNonStrikerId(), inn.openingBowlerId(), target);
    }

    /** Each innings scored from its balls, in order; the second chases the first's runs + 1. */
    List<InningsState> scores() {
        List<InningsState> states = new ArrayList<>();
        for (Innings inn : innings) {
            states.add(score(inn, inn.deliveries(), states));
        }
        return states;
    }

    /** One innings scored with these balls; {@code before} are the scores of the innings before it. */
    InningsState score(Innings inn, List<Delivery> balls, List<InningsState> before) {
        Integer target = inn.number() == 1 ? null : before.get(0).runs() + 1;
        return InningsScorer.score(rules, setup(inn, target), balls);
    }

    boolean scorer(long userId, boolean admin) {
        return admin || (scorerUserId != null && scorerUserId == userId);
    }
}
