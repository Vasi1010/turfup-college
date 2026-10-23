package com.turfup.tournament;

import com.turfup.tournament.MatchData.Innings;
import com.turfup.tournament.scoring.Delivery;
import com.turfup.tournament.scoring.ExtraType;
import com.turfup.tournament.scoring.InningsSetup;
import com.turfup.tournament.scoring.InningsState;
import com.turfup.tournament.scoring.InvalidDeliveryException;
import com.turfup.tournament.scoring.MatchOutcome;
import com.turfup.tournament.scoring.WicketType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The scorer's actions on a match: toss, start an innings, add a ball, undo a ball, close an innings early. Each is
 * one transaction that locks the match row first (then reads innings and deliveries), checks the caller is the
 * match's scorer or an admin, and returns the match as it now stands. Every ball goes through the scoring engine
 * before it is saved, so a stored innings always scores.
 *
 * <p>Conflicts with what the scorer screen showed (a ball out of turn, an undo of an older ball, an innings already
 * over) are {@link ScoreConflictException}s carrying the current match. A ball the engine refuses is an
 * {@link InvalidDeliveryException}. A ball or undo sent twice (the phone retrying) changes nothing the second time.
 */
@Service
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
public class ScoringService {

    /** Who is asking. */
    public record Actor(long userId, boolean admin) {
    }

    public record TossRequest(long winnerId, String decision) {
    }

    public record StartInningsRequest(long strikerId, long nonStrikerId, long bowlerId) {
    }

    /** One ball; see {@link Delivery}. */
    public record DeliveryRequest(UUID id, int seq, long strikerId, long nonStrikerId, long bowlerId, int runsBat,
                                  ExtraType extraType, int extraRuns, WicketType wicketType, Long playerOutId,
                                  Long fielderId) {
    }

    private final JdbcClient jdbc;
    private final MatchLoader loader;

    public ScoringService(JdbcClient jdbc, MatchLoader loader) {
        this.jdbc = jdbc;
        this.loader = loader;
    }

    /** A match the caller may score, for their list. {@code teamA}/{@code teamB}: the team's name or label. */
    public record ScorerMatch(long id, long tournamentId, String tournamentName, int matchNo, String stage,
                              OffsetDateTime scheduledAt, String status, String teamA, String teamB) {
    }

    /** The matches this user scores (an admin: every match), being played first, then the next ones. */
    @Transactional(readOnly = true)
    public List<ScorerMatch> myMatches(Actor actor) {
        return jdbc.sql("""
                        SELECT m.id, m.tournament_id, t.name AS tournament_name, m.match_no, m.stage, m.scheduled_at,
                               m.status, COALESCE(ta.name, m.team_a_label) AS team_a,
                               COALESCE(tb.name, m.team_b_label) AS team_b
                        FROM matches m JOIN tournaments t ON t.id = m.tournament_id
                        LEFT JOIN teams ta ON ta.id = m.team_a_id
                        LEFT JOIN teams tb ON tb.id = m.team_b_id
                        WHERE :admin OR m.scorer_user_id = :userId
                        ORDER BY CASE m.status WHEN 'LIVE' THEN 0 WHEN 'UPCOMING' THEN 1 ELSE 2 END,
                                 m.scheduled_at NULLS LAST, m.match_no
                        """)
                .param("admin", actor.admin())
                .param("userId", actor.userId())
                .query(ScorerMatch.class)
                .list();
    }

    /** The match as anyone may see it. */
    @Transactional(readOnly = true)
    public MatchView match(long matchId) {
        return MatchViews.of(loader.load(matchId));
    }

    /** Records the toss; it can be corrected until the first innings starts. */
    @Transactional
    public MatchView toss(long matchId, Actor actor, TossRequest req) {
        MatchData m = lockAndLoad(matchId, actor);
        if (!m.status().equals("UPCOMING") || !m.innings().isEmpty()) {
            throw conflict("The match has started: the toss can't change.", m);
        }
        if (m.teamAId() == null || m.teamBId() == null) {
            throw conflict("Both teams must be known before the toss.", m);
        }
        if (req.winnerId() != m.teamAId() && req.winnerId() != m.teamBId()) {
            throw new IllegalArgumentException("The toss winner must be one of the two teams.");
        }
        if (!"BAT".equals(req.decision()) && !"BOWL".equals(req.decision())) {
            throw new IllegalArgumentException("The toss winner chooses to BAT or BOWL.");
        }
        jdbc.sql("UPDATE matches SET toss_winner_id = :winner, toss_decision = :decision WHERE id = :id")
                .param("winner", req.winnerId())
                .param("decision", req.decision())
                .param("id", matchId)
                .update();
        return bumpAndView(matchId);
    }

    /** Starts the first innings (after the toss) or the second (after the first is over), with openers and bowler. */
    @Transactional
    public MatchView startInnings(long matchId, Actor actor, StartInningsRequest req) {
        MatchData m = lockAndLoad(matchId, actor);
        if (!m.status().equals("UPCOMING") && !m.status().equals("LIVE")) {
            throw conflict("The match is over.", m);
        }
        if (m.tossWinnerId() == null) {
            throw conflict("Record the toss first.", m);
        }
        if (m.innings().size() >= 2) {
            throw conflict("Both innings have been played.", m);
        }
        if (m.innings().size() == 1 && m.innings().get(0).live()) {
            throw conflict("The first innings is still being played.", m);
        }
        if (m.lastManStands()) {
            throw new IllegalArgumentException("\"Last man stands\" can't be scored yet.");
        }
        long batting = m.innings().isEmpty()
                ? (m.tossDecision().equals("BAT") ? m.tossWinnerId() : m.otherTeam(m.tossWinnerId()))
                : m.innings().get(0).bowlingTeamId();
        long bowling = m.otherTeam(batting);
        // Checks the openers and bowler belong to the right squads.
        new InningsSetup(m.squad(batting), m.squad(bowling), req.strikerId(), req.nonStrikerId(), req.bowlerId(), null);
        jdbc.sql("""
                        INSERT INTO innings (match_id, number, batting_team_id, bowling_team_id, opening_striker_id,
                                             opening_non_striker_id, opening_bowler_id)
                        VALUES (:matchId, :number, :batting, :bowling, :striker, :nonStriker, :bowler)
                        """)
                .param("matchId", matchId)
                .param("number", m.innings().size() + 1)
                .param("batting", batting)
                .param("bowling", bowling)
                .param("striker", req.strikerId())
                .param("nonStriker", req.nonStrikerId())
                .param("bowler", req.bowlerId())
                .update();
        jdbc.sql("UPDATE matches SET status = 'LIVE' WHERE id = :id").param("id", matchId).update();
        return bumpAndView(matchId);
    }

    /**
     * Adds a ball. Its {@code seq} must be the next one; sending a ball whose id is already saved returns the match
     * unchanged. When the ball ends the innings, the innings is closed; when it ends the second innings, the match
     * gets its result.
     */
    @Transactional
    public MatchView addDelivery(long inningsId, Actor actor, DeliveryRequest req) {
        if (req.id() == null) {
            throw new IllegalArgumentException("A ball needs an id.");
        }
        long matchId = matchOfInnings(inningsId);
        MatchData m = lockAndLoad(matchId, actor);
        Long savedIn = jdbc.sql("SELECT innings_id FROM deliveries WHERE id = :id")
                .param("id", req.id())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (savedIn != null) {
            if (savedIn != inningsId) {
                throw conflict("This ball was recorded in another innings.", m);
            }
            return MatchViews.of(m); // a retry: already saved
        }
        Innings inn = m.innings(inningsId).orElseThrow(() -> new MatchNotFoundException("innings", inningsId));
        if (!inn.live() || !m.status().equals("LIVE")) {
            throw conflict("This innings is over.", m);
        }
        int next = inn.deliveries().size() + 1;
        if (req.seq() != next) {
            throw conflict("The score has moved on: the next ball is number " + next + ".", m);
        }
        Delivery ball = new Delivery(req.id(), req.seq(), req.strikerId(), req.nonStrikerId(), req.bowlerId(),
                req.runsBat(), req.extraType(), req.extraRuns(), req.wicketType(), req.playerOutId(), req.fielderId());
        List<InningsState> before = scoresBefore(m, inn);
        List<Delivery> balls = new ArrayList<>(inn.deliveries());
        balls.add(ball);
        InningsState state = m.score(inn, balls, before); // refuses a ball that can't follow the others

        jdbc.sql("""
                        INSERT INTO deliveries (id, innings_id, seq, striker_id, non_striker_id, bowler_id, runs_bat,
                                                extra_type, extra_runs, wicket_type, player_out_id, fielder_id,
                                                created_by)
                        VALUES (:id, :inningsId, :seq, :striker, :nonStriker, :bowler, :runsBat, :extraType,
                                :extraRuns, :wicketType, :playerOut, :fielder, :createdBy)
                        """)
                .param("id", ball.id())
                .param("inningsId", inningsId)
                .param("seq", ball.seq())
                .param("striker", ball.strikerId())
                .param("nonStriker", ball.nonStrikerId())
                .param("bowler", ball.bowlerId())
                .param("runsBat", ball.runsBat())
                .param("extraType", ball.extraType() == null ? null : ball.extraType().name())
                .param("extraRuns", ball.extraRuns())
                .param("wicketType", ball.wicketType() == null ? null : ball.wicketType().name())
                .param("playerOut", ball.playerOutId())
                .param("fielder", ball.fielderId())
                .param("createdBy", actor.userId())
                .update();

        if (state.ended()) {
            jdbc.sql("UPDATE innings SET status = 'DONE', end_reason = :reason, ended_at = now() WHERE id = :id")
                    .param("reason", state.endReason().name())
                    .param("id", inningsId)
                    .update();
            if (inn.number() == 2) {
                MatchOutcome.decide(before.get(0), state).ifPresent(o -> recordResult(m, o));
            }
        }
        return bumpAndView(matchId);
    }

    /**
     * Undoes a ball: only the latest of its innings, and not once the next innings has started. Undoing a ball that
     * ended the innings (or the match) reopens it. Undoing a ball already undone changes nothing.
     */
    @Transactional
    public MatchView undo(UUID deliveryId, Actor actor) {
        long matchId = jdbc.sql("""
                        SELECT i.match_id FROM deliveries d JOIN innings i ON i.id = d.innings_id WHERE d.id = :id
                        """)
                .param("id", deliveryId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new MatchNotFoundException("ball", deliveryId));
        MatchData m = lockAndLoad(matchId, actor);
        // Read under the lock, so two undos of the same ball at once can't both act on it.
        record Ball(long inningsId, int seq, boolean deleted, int number) {
        }
        Ball ball = jdbc.sql("""
                        SELECT d.innings_id, d.seq, d.deleted_at IS NOT NULL AS deleted, i.number
                        FROM deliveries d JOIN innings i ON i.id = d.innings_id
                        WHERE d.id = :id
                        """)
                .param("id", deliveryId)
                .query(Ball.class)
                .single();
        if (ball.deleted()) {
            return MatchViews.of(m); // a retry: already undone
        }
        Innings inn = m.innings(ball.inningsId()).orElseThrow();
        if (m.innings().size() > ball.number()) {
            throw conflict("The next innings has started: this ball can't be undone.", m);
        }
        if (inn.deliveries().isEmpty() || inn.deliveries().get(inn.deliveries().size() - 1).seq() != ball.seq()) {
            throw conflict("Only the latest ball can be undone.", m);
        }
        jdbc.sql("UPDATE deliveries SET deleted_at = now() WHERE id = :id").param("id", deliveryId).update();
        if (!inn.live()) {
            jdbc.sql("UPDATE innings SET status = 'LIVE', end_reason = NULL, ended_at = NULL WHERE id = :id")
                    .param("id", inn.id())
                    .update();
        }
        if (m.status().equals("DONE")) {
            jdbc.sql("""
                            UPDATE matches SET status = 'LIVE', result = NULL, winner_id = NULL, result_note = NULL
                            WHERE id = :id
                            """)
                    .param("id", m.id())
                    .update();
        }
        return bumpAndView(m.id());
    }

    /** Closes an innings early, e.g. for rain. The second innings closed this way leaves the result to an admin. */
    @Transactional
    public MatchView endInnings(long inningsId, Actor actor) {
        long matchId = matchOfInnings(inningsId);
        MatchData m = lockAndLoad(matchId, actor);
        Innings inn = m.innings(inningsId).orElseThrow(() -> new MatchNotFoundException("innings", inningsId));
        if (!inn.live()) {
            throw conflict("This innings is already over.", m);
        }
        jdbc.sql("UPDATE innings SET status = 'DONE', end_reason = 'CLOSED', ended_at = now() WHERE id = :id")
                .param("id", inningsId)
                .update();
        return bumpAndView(matchId);
    }

    private MatchData lockAndLoad(long matchId, Actor actor) {
        loader.lock(matchId);
        MatchData m = loader.load(matchId);
        if (!m.scorer(actor.userId(), actor.admin())) {
            throw new NotMatchScorerException();
        }
        return m;
    }

    private long matchOfInnings(long inningsId) {
        return jdbc.sql("SELECT match_id FROM innings WHERE id = :id")
                .param("id", inningsId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new MatchNotFoundException("innings", inningsId));
    }

    /** The scores of the innings before {@code inn}, which a second innings needs for its target. */
    private static List<InningsState> scoresBefore(MatchData m, Innings inn) {
        List<InningsState> states = new ArrayList<>();
        for (Innings earlier : m.innings()) {
            if (earlier.number() >= inn.number()) {
                break;
            }
            states.add(m.score(earlier, earlier.deliveries(), states));
        }
        return states;
    }

    private void recordResult(MatchData m, MatchOutcome o) {
        Long winner = o.result() == MatchOutcome.Result.TIE ? null
                : o.winningInnings() == 1 ? m.innings().get(0).battingTeamId() : m.innings().get(1).battingTeamId();
        jdbc.sql("UPDATE matches SET status = 'DONE', result = :result, winner_id = :winner WHERE id = :id")
                .param("result", o.result().name())
                .param("winner", winner)
                .param("id", m.id())
                .update();
    }

    private MatchView bumpAndView(long matchId) {
        jdbc.sql("UPDATE matches SET version = version + 1 WHERE id = :id").param("id", matchId).update();
        return MatchViews.of(loader.load(matchId));
    }

    private static ScoreConflictException conflict(String message, MatchData m) {
        return new ScoreConflictException(message, MatchViews.of(m));
    }
}
