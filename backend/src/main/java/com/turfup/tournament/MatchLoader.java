package com.turfup.tournament;

import com.turfup.tournament.MatchData.Innings;
import com.turfup.tournament.MatchData.Team;
import com.turfup.tournament.scoring.Delivery;
import com.turfup.tournament.scoring.ExtraType;
import com.turfup.tournament.scoring.ScoringRules;
import com.turfup.tournament.scoring.WicketType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** Reads a match and everything scoring needs ({@link MatchData}), inside the caller's transaction. */
@Component
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
class MatchLoader {

    private final JdbcClient jdbc;

    MatchLoader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the match row: every scoring change takes this lock first (then innings, then deliveries). */
    void lock(long matchId) {
        jdbc.sql("SELECT id FROM matches WHERE id = :id FOR UPDATE")
                .param("id", matchId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new MatchNotFoundException("match", matchId));
    }

    /** @throws MatchNotFoundException if there is no such match */
    MatchData load(long matchId) {
        record Head(long id, long tournamentId, int matchNo, String stage, OffsetDateTime scheduledAt, String status,
                    Long teamAId, Long teamBId, String teamALabel, String teamBLabel, Long tossWinnerId,
                    String tossDecision, String result, Long winnerId, String resultNote, Long scorerUserId,
                    int version, ScoringRules rules, boolean lastManStands) {
        }
        Head h = jdbc.sql("""
                        SELECT m.id, m.tournament_id, m.match_no, m.stage, m.scheduled_at, m.status, m.team_a_id,
                               m.team_b_id, m.team_a_label, m.team_b_label, m.toss_winner_id, m.toss_decision, m.result,
                               m.winner_id, m.result_note, m.scorer_user_id, m.version, m.overs_per_innings,
                               t.overs_per_innings AS tournament_overs, t.max_overs_per_bowler, t.balls_per_over,
                               t.players_per_side, t.wide_runs, t.no_ball_runs,
                               COALESCE((t.local_rules ->> 'noLbw')::boolean, false) AS no_lbw,
                               COALESCE((t.local_rules ->> 'freeHit')::boolean, false) AS free_hit,
                               COALESCE((t.local_rules ->> 'lastManStands')::boolean, false) AS last_man_stands
                        FROM matches m JOIN tournaments t ON t.id = m.tournament_id
                        WHERE m.id = :id
                        """)
                .param("id", matchId)
                .query((rs, n) -> {
                    ScoringRules rules = new ScoringRules(rs.getInt("tournament_overs"),
                            (Integer) rs.getObject("max_overs_per_bowler"), rs.getInt("balls_per_over"),
                            rs.getInt("players_per_side"), rs.getInt("wide_runs"), rs.getInt("no_ball_runs"),
                            rs.getBoolean("no_lbw"), rs.getBoolean("free_hit"));
                    Integer overs = (Integer) rs.getObject("overs_per_innings");
                    return new Head(rs.getLong("id"), rs.getLong("tournament_id"), rs.getInt("match_no"),
                            rs.getString("stage"), rs.getObject("scheduled_at", OffsetDateTime.class),
                            rs.getString("status"), longOrNull(rs, "team_a_id"), longOrNull(rs, "team_b_id"),
                            rs.getString("team_a_label"), rs.getString("team_b_label"),
                            longOrNull(rs, "toss_winner_id"), rs.getString("toss_decision"), rs.getString("result"),
                            longOrNull(rs, "winner_id"), rs.getString("result_note"),
                            longOrNull(rs, "scorer_user_id"), rs.getInt("version"),
                            overs == null ? rules : rules.withOvers(overs), rs.getBoolean("last_man_stands"));
                })
                .optional()
                .orElseThrow(() -> new MatchNotFoundException("match", matchId));

        List<Long> teamIds = Stream.of(h.teamAId(), h.teamBId()).filter(Objects::nonNull).toList();
        Map<Long, Team> teams = new LinkedHashMap<>();
        Map<Long, List<Long>> squads = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        if (!teamIds.isEmpty()) {
            jdbc.sql("SELECT id, name, short_name FROM teams WHERE id IN (:ids)")
                    .param("ids", teamIds)
                    .query(Team.class)
                    .list()
                    .forEach(t -> teams.put(t.id(), t));
            jdbc.sql("""
                            SELECT sm.team_id, c.id, c.name
                            FROM squad_members sm JOIN cricketers c ON c.id = sm.cricketer_id
                            WHERE sm.team_id IN (:ids)
                            ORDER BY sm.team_id, sm.created_at, c.id
                            """)
                    .param("ids", teamIds)
                    .query((rs, n) -> {
                        long teamId = rs.getLong("team_id");
                        long id = rs.getLong("id");
                        squads.computeIfAbsent(teamId, k -> new ArrayList<>()).add(id);
                        names.put(id, rs.getString("name"));
                        return id;
                    })
                    .list();
        }

        record Row(long id, int number, long battingTeamId, long bowlingTeamId, long openingStrikerId,
                   long openingNonStrikerId, long openingBowlerId, String status, String endReason) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT id, number, batting_team_id, bowling_team_id, opening_striker_id, opening_non_striker_id,
                               opening_bowler_id, status, end_reason
                        FROM innings WHERE match_id = :id ORDER BY number
                        """)
                .param("id", matchId)
                .query(Row.class)
                .list();
        Map<Long, List<Delivery>> balls = new HashMap<>();
        if (!rows.isEmpty()) {
            jdbc.sql("""
                            SELECT id, innings_id, seq, striker_id, non_striker_id, bowler_id, runs_bat, extra_type,
                                   extra_runs, wicket_type, player_out_id, fielder_id
                            FROM deliveries
                            WHERE innings_id IN (:ids) AND deleted_at IS NULL
                            ORDER BY innings_id, seq
                            """)
                    .param("ids", rows.stream().map(Row::id).toList())
                    .query((rs, n) -> {
                        String extra = rs.getString("extra_type");
                        String wicket = rs.getString("wicket_type");
                        Delivery d = new Delivery(rs.getObject("id", UUID.class), rs.getInt("seq"),
                                rs.getLong("striker_id"), rs.getLong("non_striker_id"), rs.getLong("bowler_id"),
                                rs.getInt("runs_bat"), extra == null ? null : ExtraType.valueOf(extra),
                                rs.getInt("extra_runs"), wicket == null ? null : WicketType.valueOf(wicket),
                                longOrNull(rs, "player_out_id"), longOrNull(rs, "fielder_id"));
                        balls.computeIfAbsent(rs.getLong("innings_id"), k -> new ArrayList<>()).add(d);
                        return d;
                    })
                    .list();
        }
        List<Innings> innings = rows.stream().map(r -> new Innings(r.id(), r.number(), r.battingTeamId(),
                r.bowlingTeamId(), r.openingStrikerId(), r.openingNonStrikerId(), r.openingBowlerId(), r.status(),
                r.endReason(), List.copyOf(balls.getOrDefault(r.id(), List.of())))).toList();

        return new MatchData(h.id(), h.tournamentId(), h.matchNo(), h.stage(), h.scheduledAt(), h.status(),
                h.teamAId(), h.teamBId(), h.teamALabel(), h.teamBLabel(), h.tossWinnerId(), h.tossDecision(),
                h.result(), h.winnerId(), h.resultNote(), h.scorerUserId(), h.version(), h.rules(), h.lastManStands(),
                teams, squads, names, innings);
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
