package com.turfup.tournament;

import com.turfup.tournament.MatchData.Innings;
import com.turfup.tournament.MatchData.Team;
import com.turfup.tournament.MatchView.BallSummary;
import com.turfup.tournament.MatchView.BattingLine;
import com.turfup.tournament.MatchView.BowlingLine;
import com.turfup.tournament.MatchView.Extras;
import com.turfup.tournament.MatchView.FallOfWicket;
import com.turfup.tournament.MatchView.InningsCard;
import com.turfup.tournament.MatchView.LiveState;
import com.turfup.tournament.MatchView.Named;
import com.turfup.tournament.MatchView.Side;
import com.turfup.tournament.MatchView.Toss;
import com.turfup.tournament.scoring.InningsState;
import com.turfup.tournament.scoring.InningsState.Dismissal;
import com.turfup.tournament.scoring.MatchOutcome;
import com.turfup.tournament.scoring.ScoringRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Builds the {@link MatchView} response from a loaded match: the engine's figures plus names and run rates. */
final class MatchViews {

    private MatchViews() {
    }

    static MatchView of(MatchData m) {
        List<InningsState> states = m.scores();
        List<InningsCard> cards = new java.util.ArrayList<>();
        LiveState live = null;
        for (int i = 0; i < m.innings().size(); i++) {
            Innings inn = m.innings().get(i);
            InningsState s = states.get(i);
            // The engine says LIVE until the balls end it; the scorer may also close an innings early (CLOSED).
            boolean liveHere = inn.live() && !s.ended() && m.status().equals("LIVE");
            cards.add(card(m, inn, s, liveHere));
            if (liveHere) {
                live = live(m.rules(), inn, s);
            }
        }
        MatchOutcome outcome = states.size() == 2 ? MatchOutcome.decide(states.get(0), states.get(1)).orElse(null) : null;
        return new MatchView(m.id(), m.tournamentId(), m.matchNo(), m.stage(), m.scheduledAt(), m.status(),
                m.rules().oversPerInnings(), m.rules(), side(m, m.teamAId(), m.teamALabel()),
                side(m, m.teamBId(), m.teamBLabel()),
                m.tossWinnerId() == null ? null : new Toss(m.tossWinnerId(), m.tossDecision()), List.copyOf(cards),
                live, m.result(), m.winnerId(), resultText(m, outcome), m.resultNote(), m.version());
    }

    private static Side side(MatchData m, Long teamId, String label) {
        Team t = teamId == null ? null : m.teams().get(teamId);
        if (t == null) {
            return new Side(null, label, null, null, List.of());
        }
        List<Named> squad = m.squad(t.id()).stream().map(id -> new Named(id, m.names().get(id))).toList();
        return new Side(t.id(), t.name(), t.name(), t.shortName(), squad);
    }

    private static InningsCard card(MatchData m, Innings inn, InningsState s, boolean live) {
        String status = live ? "LIVE" : "DONE";
        String endReason = inn.endReason() != null ? inn.endReason() : s.ended() ? s.endReason().name() : null;
        Long striker = live && s.live() != null ? s.live().strikerId() : null;
        Long bowler = live && s.live() != null ? s.live().bowlerId() : null;
        List<BattingLine> batting = s.batting().stream().map(b -> new BattingLine(b.cricketerId(),
                m.names().get(b.cricketerId()), live || b.status() != InningsState.BatterStatus.BATTING
                ? b.status().name() : "NOT_OUT", dismissal(m, b.dismissal()), b.runs(), b.balls(), b.fours(),
                b.sixes(), rate(b.runs() * 100L, b.balls(), 1), Objects.equals(striker, b.cricketerId()))).toList();
        Set<Long> batted = new HashSet<>();
        s.batting().forEach(b -> batted.add(b.cricketerId()));
        List<Named> yetToBat = m.squad(inn.battingTeamId()).stream().filter(id -> !batted.contains(id))
                .map(id -> new Named(id, m.names().get(id))).toList();
        List<BowlingLine> bowling = s.bowling().stream().map(b -> new BowlingLine(b.cricketerId(),
                m.names().get(b.cricketerId()), b.legalBalls(), b.overs(), b.maidens(), b.runs(), b.wickets(),
                b.wides(), b.noBalls(), rate((long) b.runs() * m.rules().ballsPerOver(), b.legalBalls(), 2),
                Objects.equals(bowler, b.cricketerId()))).toList();
        List<FallOfWicket> fow = s.fallOfWickets().stream().map(f -> new FallOfWicket(f.wicket(), f.runs(), f.overs(),
                f.cricketerId(), m.names().get(f.cricketerId()))).toList();
        InningsState.Extras e = s.extras();
        return new InningsCard(inn.id(), inn.number(), inn.battingTeamId(), inn.bowlingTeamId(), status, s.runs(),
                s.wickets(), s.legalBalls(), s.overs(), endReason,
                new Extras(e.wides(), e.noBalls(), e.byes(), e.legByes(), e.total()), s.target(), batting, yetToBat,
                bowling, fow, inn.deliveries().isEmpty() ? null
                        : inn.deliveries().get(inn.deliveries().size() - 1).id());
    }

    private static LiveState live(ScoringRules rules, Innings inn, InningsState s) {
        InningsState.Live l = s.live();
        int currentOver = s.legalBalls() / rules.ballsPerOver();
        List<BallSummary> thisOver = l.newOver() ? List.of()
                : s.balls().stream().filter(b -> b.over() == currentOver).map(MatchViews::ball).toList();
        List<BallSummary> recent = s.balls().subList(Math.max(0, s.balls().size() - 12), s.balls().size()).stream()
                .map(MatchViews::ball).toList();
        BigDecimal required = s.runsNeeded() == null || s.ballsLeft() == 0 ? null
                : rate((long) s.runsNeeded() * rules.ballsPerOver(), s.ballsLeft(), 2);
        return new LiveState(inn.id(), inn.number(), l.strikerId(), l.nonStrikerId(), l.bowlerId(),
                l.lastOverBowlerId(), l.nextSeq(), thisOver, recent,
                rate((long) s.runs() * rules.ballsPerOver(), s.legalBalls(), 2), s.runsNeeded(), s.ballsLeft(),
                required, l.eligibleBowlerIds(), l.availableBatterIds(), l.newOver(), l.needsNewBatter(), l.freeHit());
    }

    private static BallSummary ball(InningsState.BallSummary b) {
        return new BallSummary(b.deliveryId(), b.label(), b.runs(), b.legal(), b.wicket(), b.boundary(), b.freeHit());
    }

    /** "c Khan b Patil", "c & b Patil", "run out (Pawar)", "st More b Patil", "lbw b Patil"; surnames. */
    static String dismissal(MatchData m, Dismissal d) {
        if (d == null) {
            return null;
        }
        String bowler = d.bowlerId() == null ? null : surname(m.names().get(d.bowlerId()));
        String fielder = d.fielderId() == null ? null : surname(m.names().get(d.fielderId()));
        return switch (d.type()) {
            case BOWLED -> "b " + bowler;
            case CAUGHT -> Objects.equals(d.fielderId(), d.bowlerId()) ? "c & b " + bowler
                    : (fielder == null ? "c" : "c " + fielder) + " b " + bowler;
            case LBW -> "lbw b " + bowler;
            case RUN_OUT -> fielder == null ? "run out" : "run out (" + fielder + ")";
            case STUMPED -> (fielder == null ? "st" : "st " + fielder) + " b " + bowler;
            case HIT_WICKET -> "hit wicket b " + bowler;
            case RETIRED_OUT -> "retired out";
            case RETIRED_HURT -> "retired hurt";
        };
    }

    private static String surname(String name) {
        if (name == null) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    /** numerator ÷ denominator to {@code scale} decimals; 0 when nothing to divide by. */
    private static BigDecimal rate(long numerator, int denominator, int scale) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    /** "Cidco Strikers won by 12 runs", "… won by 3 wickets (5 balls left)", "Match tied", "No result". */
    private static String resultText(MatchData m, MatchOutcome outcome) {
        if (m.result() == null) {
            return null;
        }
        String winner = m.winnerId() == null ? null : Optional.ofNullable(m.teams().get(m.winnerId()))
                .map(Team::name).orElse(null);
        return switch (m.result()) {
            case "TIE" -> "Match tied";
            case "NO_RESULT" -> "No result";
            default -> {
                if (outcome == null || outcome.result() != MatchOutcome.Result.WIN) {
                    yield winner + " won";
                }
                if (outcome.winningInnings() == 1) {
                    yield winner + " won by " + plural(outcome.runMargin(), "run");
                }
                yield winner + " won by " + plural(outcome.wicketMargin(), "wicket")
                        + (outcome.ballsLeft() > 0 ? " (" + plural(outcome.ballsLeft(), "ball") + " left)" : "");
            }
        };
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
