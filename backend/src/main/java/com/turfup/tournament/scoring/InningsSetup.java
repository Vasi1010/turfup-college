package com.turfup.tournament.scoring;

import java.util.HashSet;
import java.util.List;

/**
 * Who can play in an innings, and how it starts.
 *
 * @param battingSquad  the batting team's squad: anyone not yet batted may come in
 * @param bowlingSquad  the bowling team's squad: anyone may bowl
 * @param strikerId     the opener facing the first ball
 * @param nonStrikerId  the other opener
 * @param bowlerId      the opening bowler
 * @param target        runs to win; null in the first innings
 */
public record InningsSetup(List<Long> battingSquad, List<Long> bowlingSquad, long strikerId, long nonStrikerId,
                           long bowlerId, Integer target) {

    public InningsSetup {
        battingSquad = List.copyOf(battingSquad);
        bowlingSquad = List.copyOf(bowlingSquad);
        if (new HashSet<>(battingSquad).size() != battingSquad.size()
                || new HashSet<>(bowlingSquad).size() != bowlingSquad.size()) {
            throw new IllegalArgumentException("A squad lists someone twice.");
        }
        if (battingSquad.stream().anyMatch(bowlingSquad::contains)) {
            throw new IllegalArgumentException("Someone is in both squads.");
        }
        if (strikerId == nonStrikerId) {
            throw new IllegalArgumentException("The two openers must be different players.");
        }
        if (!battingSquad.contains(strikerId) || !battingSquad.contains(nonStrikerId)) {
            throw new IllegalArgumentException("The openers must be in the batting team's squad.");
        }
        if (!bowlingSquad.contains(bowlerId)) {
            throw new IllegalArgumentException("The bowler must be in the bowling team's squad.");
        }
        if (target != null && target < 1) {
            throw new IllegalArgumentException("The target must be at least 1 run.");
        }
    }
}
