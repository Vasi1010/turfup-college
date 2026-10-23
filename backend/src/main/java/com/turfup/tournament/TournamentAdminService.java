package com.turfup.tournament;

import com.turfup.tournament.TournamentView.Rules;
import com.turfup.tournament.scoring.ScoringRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Setting up a tournament (task B5a, ADMIN only): the tournament with its rules, teams with pasted squads, fixtures,
 * and match changes (reschedule, the final's teams once known, the scorer, abandon, record or correct a result).
 * Bad input is an {@link IllegalArgumentException} (400); a change the match's state doesn't allow is a
 * {@link ScoreConflictException} (409).
 */
@Service
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
public class TournamentAdminService {

    public record CreateTournament(String name, String venueName, LocalDate startDate, LocalDate endDate,
                                   Rules rules) {
    }

    /** {@code squad}: pasted lines, "7 Rahul Patil" or "Rahul Patil"; "(c)" marks the captain. */
    public record AddTeam(String name, String shortName, String groupName, String squad) {
    }

    /** A team id, or a label (e.g. "Winner SF 1") while the team isn't known. */
    public record SideRequest(Long teamId, String label) {
    }

    /** {@code scorerPhone}: the scorer's TurfUp account (they sign up first); null for none yet. */
    public record CreateMatch(Integer matchNo, String stage, SideRequest teamA, SideRequest teamB,
                              OffsetDateTime scheduledAt, String scorerPhone) {
    }

    /** Who scores a match, for the admin screens (not public: it has the scorer's phone). */
    public record MatchScorer(long matchId, String name, String phone) {
    }

    /** The scorer of each match of a tournament that has one. */
    @Transactional(readOnly = true)
    public List<MatchScorer> scorers(long tournamentId) {
        requireTournament(tournamentId);
        return jdbc.sql("""
                        SELECT m.id AS match_id, u.name, u.phone
                        FROM matches m JOIN users u ON u.id = m.scorer_user_id
                        WHERE m.tournament_id = :t
                        ORDER BY m.match_no
                        """)
                .param("t", tournamentId)
                .query(MatchScorer.class)
                .list();
    }

    /** One field of a partial update: null when the field was not sent; {@code value} may itself be null. */
    public record Change<T>(T value) {
    }

    /** {@code PATCH /api/matches/{id}}: only the fields sent change. */
    public record MatchPatch(Change<OffsetDateTime> scheduledAt, Change<SideRequest> teamA, Change<SideRequest> teamB,
                             Change<Integer> oversPerInnings, Change<String> scorerPhone, Change<String> status,
                             Change<String> result, Change<Long> winnerId, Change<String> resultNote) {
    }

    private static final Pattern SQUAD_LINE = Pattern.compile("^(?:#?(\\d{1,3})[.)]?\\s+)?(.+)$");
    private static final Pattern CAPTAIN = Pattern.compile("\\(\\s*c\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STAGES = Set.of("GROUP", "QUARTER_FINAL", "SEMI_FINAL", "FINAL");

    private final JdbcClient jdbc;
    private final MatchLoader loader;
    private final TournamentQueryService query;

    public TournamentAdminService(JdbcClient jdbc, MatchLoader loader, TournamentQueryService query) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.query = query;
    }

    @Transactional
    public TournamentView.Detail createTournament(long adminId, CreateTournament req) {
        String name = required(req.name(), "The tournament needs a name.", 120);
        String venue = required(req.venueName(), "Say where it is played.", 120);
        if (req.startDate() == null || req.endDate() == null || req.endDate().isBefore(req.startDate())) {
            throw new IllegalArgumentException("The tournament needs a start date and an end date on or after it.");
        }
        Rules r = req.rules();
        if (r == null) {
            throw new IllegalArgumentException("The tournament needs its rules.");
        }
        // Checks overs, bowler limit, balls per over, players per side, wide and no-ball runs.
        new ScoringRules(r.oversPerInnings(), r.maxOversPerBowler(), r.ballsPerOver(), r.playersPerSide(),
                r.wideRuns(), r.noBallRuns(), false, false);
        if (r.pointsWin() <= r.pointsTie() || r.pointsTie() < 0 || r.pointsNoResult() < 0) {
            throw new IllegalArgumentException("A win must be worth more than a tie, and no points can be negative.");
        }
        if (!"NRR".equals(r.tiebreaker()) && !"HEAD_TO_HEAD".equals(r.tiebreaker())) {
            throw new IllegalArgumentException("The tiebreaker is NRR or HEAD_TO_HEAD.");
        }
        TournamentView.LocalRules local = r.localRules() == null
                ? new TournamentView.LocalRules(false, false, false) : r.localRules();
        long id = jdbc.sql("""
                        INSERT INTO tournaments (name, venue_name, start_date, end_date, overs_per_innings,
                                                 max_overs_per_bowler, balls_per_over, players_per_side, wide_runs,
                                                 no_ball_runs, points_win, points_tie, points_no_result, tiebreaker,
                                                 local_rules, created_by)
                        VALUES (:name, :venue, :start, :end, :overs, :maxOvers, :bpo, :pps, :wide, :noBall, :win,
                                :tie, :noResult, :tiebreaker, CAST(:localRules AS jsonb), :createdBy)
                        RETURNING id
                        """)
                .param("name", name)
                .param("venue", venue)
                .param("start", req.startDate())
                .param("end", req.endDate())
                .param("overs", r.oversPerInnings())
                .param("maxOvers", r.maxOversPerBowler())
                .param("bpo", r.ballsPerOver())
                .param("pps", r.playersPerSide())
                .param("wide", r.wideRuns())
                .param("noBall", r.noBallRuns())
                .param("win", r.pointsWin())
                .param("tie", r.pointsTie())
                .param("noResult", r.pointsNoResult())
                .param("tiebreaker", r.tiebreaker())
                .param("localRules", "{\"noLbw\": %b, \"freeHit\": %b, \"lastManStands\": %b}"
                        .formatted(local.noLbw(), local.freeHit(), local.lastManStands()))
                .param("createdBy", adminId)
                .query(Long.class)
                .single();
        return query.detail(id);
    }

    /** Adds a team and its squad, pasted one cricketer per line. */
    @Transactional
    public TournamentView.Team addTeam(long tournamentId, AddTeam req) {
        requireTournament(tournamentId);
        String name = required(req.name(), "The team needs a name.", 60);
        String shortName = req.shortName() == null ? "" : req.shortName().strip().toUpperCase(Locale.ROOT);
        if (!shortName.matches("[A-Z0-9]{2,5}")) {
            throw new IllegalArgumentException("The short name is 2 to 5 letters or digits, e.g. CID.");
        }
        String group = req.groupName() == null || req.groupName().isBlank() ? null : req.groupName().strip();
        if (group != null && group.length() > 20) {
            throw new IllegalArgumentException("A group name is at most 20 characters.");
        }

        record Member(String name, Integer jersey, boolean captain) {
        }
        List<Member> squad = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Integer> jerseys = new HashSet<>();
        for (String line : (req.squad() == null ? "" : req.squad()).split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            boolean captain = CAPTAIN.matcher(text).find();
            text = CAPTAIN.matcher(text).replaceAll("").strip();
            Matcher m = SQUAD_LINE.matcher(text);
            if (!m.matches() || m.group(2).isBlank()) {
                throw new IllegalArgumentException("Can't read this squad line: \"" + line.strip() + "\".");
            }
            String player = m.group(2).strip().replaceAll("\\s+", " ");
            Integer jersey = m.group(1) == null ? null : Integer.valueOf(m.group(1));
            if (player.length() > 120) {
                throw new IllegalArgumentException("A name is at most 120 characters: \"" + player + "\".");
            }
            if (!names.add(player.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(player + " is listed twice.");
            }
            if (jersey != null && !jerseys.add(jersey)) {
                throw new IllegalArgumentException("Jersey " + jersey + " is listed twice.");
            }
            squad.add(new Member(player, jersey, captain));
        }
        if (squad.size() < 2) {
            throw new IllegalArgumentException("A squad needs at least 2 players, one per line.");
        }
        if (squad.stream().filter(Member::captain).count() > 1) {
            throw new IllegalArgumentException("Mark only one captain with (c).");
        }

        long teamId;
        try {
            teamId = jdbc.sql("""
                            INSERT INTO teams (tournament_id, name, short_name, group_name)
                            VALUES (:t, :name, :short, :group) RETURNING id
                            """)
                    .param("t", tournamentId)
                    .param("name", name)
                    .param("short", shortName)
                    .param("group", group)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A team with that name or short name is already in this tournament.");
        }
        Long captainId = null;
        for (Member member : squad) {
            long id = jdbc.sql("INSERT INTO cricketers (name) VALUES (:name) RETURNING id")
                    .param("name", member.name())
                    .query(Long.class)
                    .single();
            jdbc.sql("""
                            INSERT INTO squad_members (team_id, tournament_id, cricketer_id, jersey_no)
                            VALUES (:team, :t, :c, :jersey)
                            """)
                    .param("team", teamId)
                    .param("t", tournamentId)
                    .param("c", id)
                    .param("jersey", member.jersey())
                    .update();
            if (member.captain()) {
                captainId = id;
            }
        }
        if (captainId != null) {
            jdbc.sql("UPDATE teams SET captain_id = :c WHERE id = :id").param("c", captainId).param("id", teamId)
                    .update();
        }
        long finalTeamId = teamId;
        return query.teams(tournamentId).stream().filter(t -> t.id() == finalTeamId).findFirst().orElseThrow();
    }

    @Transactional
    public MatchView createMatch(long tournamentId, CreateMatch req) {
        requireTournament(tournamentId);
        if (req.matchNo() == null || req.matchNo() < 1) {
            throw new IllegalArgumentException("Give the match a number, from 1.");
        }
        String stage = req.stage() == null ? "GROUP" : req.stage();
        if (!STAGES.contains(stage)) {
            throw new IllegalArgumentException("The stage is GROUP, QUARTER_FINAL, SEMI_FINAL or FINAL.");
        }
        SideRequest a = side(tournamentId, req.teamA());
        SideRequest b = side(tournamentId, req.teamB());
        if (a.teamId() != null && a.teamId().equals(b.teamId())) {
            throw new IllegalArgumentException("A team can't play itself.");
        }
        Long scorer = req.scorerPhone() == null || req.scorerPhone().isBlank() ? null : userByPhone(req.scorerPhone());
        long id;
        try {
            id = jdbc.sql("""
                            INSERT INTO matches (tournament_id, match_no, stage, team_a_id, team_b_id, team_a_label,
                                                 team_b_label, scheduled_at, scorer_user_id)
                            VALUES (:t, :no, :stage, :a, :b, :aLabel, :bLabel, :at, :scorer) RETURNING id
                            """)
                    .param("t", tournamentId)
                    .param("no", req.matchNo())
                    .param("stage", stage)
                    .param("a", a.teamId())
                    .param("b", b.teamId())
                    .param("aLabel", a.label())
                    .param("bLabel", b.label())
                    .param("at", req.scheduledAt())
                    .param("scorer", scorer)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Match " + req.matchNo() + " already exists in this tournament.");
        }
        return MatchViews.of(loader.load(id));
    }

    /**
     * Changes a match. Teams and overs only before play starts. A result can be recorded once no innings is being
     * played (a closed innings, rain) and corrected after the match is over; ABANDONED ends it with no result.
     */
    @Transactional
    public MatchView updateMatch(long matchId, MatchPatch p) {
        loader.lock(matchId);
        MatchData m = loader.load(matchId);
        boolean started = !m.innings().isEmpty();

        if (p.scheduledAt() != null) {
            set(matchId, "scheduled_at", p.scheduledAt().value());
        }
        if (p.teamA() != null || p.teamB() != null) {
            if (started || !m.status().equals("UPCOMING")) {
                throw conflict("The teams can't change once the match has started.", m);
            }
            SideRequest a = p.teamA() == null ? new SideRequest(m.teamAId(), m.teamALabel())
                    : side(m.tournamentId(), p.teamA().value());
            SideRequest b = p.teamB() == null ? new SideRequest(m.teamBId(), m.teamBLabel())
                    : side(m.tournamentId(), p.teamB().value());
            if (a.teamId() != null && a.teamId().equals(b.teamId())) {
                throw new IllegalArgumentException("A team can't play itself.");
            }
            jdbc.sql("""
                            UPDATE matches SET team_a_id = :a, team_a_label = :aLabel, team_b_id = :b,
                                               team_b_label = :bLabel, toss_winner_id = NULL, toss_decision = NULL
                            WHERE id = :id
                            """)
                    .param("a", a.teamId())
                    .param("aLabel", a.label())
                    .param("b", b.teamId())
                    .param("bLabel", b.label())
                    .param("id", matchId)
                    .update();
        }
        if (p.oversPerInnings() != null) {
            if (started) {
                throw conflict("The overs can't change once play has started.", m);
            }
            Integer overs = p.oversPerInnings().value();
            if (overs != null && (overs < 1 || overs > 50)) {
                throw new IllegalArgumentException("Overs per innings must be between 1 and 50.");
            }
            set(matchId, "overs_per_innings", overs);
        }
        if (p.scorerPhone() != null) {
            String phone = p.scorerPhone().value();
            set(matchId, "scorer_user_id", phone == null || phone.isBlank() ? null : userByPhone(phone));
        }
        if (p.status() != null) {
            if (!"ABANDONED".equals(p.status().value())) {
                throw new IllegalArgumentException("The only status an admin sets is ABANDONED.");
            }
            if (m.status().equals("DONE")) {
                throw conflict("The match is over; correct its result instead.", m);
            }
            jdbc.sql("""
                            UPDATE innings SET status = 'DONE', end_reason = 'CLOSED', ended_at = now()
                            WHERE match_id = :id AND status = 'LIVE'
                            """)
                    .param("id", matchId)
                    .update();
            jdbc.sql("""
                            UPDATE matches SET status = 'ABANDONED', result = 'NO_RESULT', winner_id = NULL
                            WHERE id = :id
                            """)
                    .param("id", matchId)
                    .update();
        }
        if (p.result() != null) {
            recordResult(m, p.result().value(), p.winnerId() == null ? null : p.winnerId().value());
        } else if (p.winnerId() != null) {
            throw new IllegalArgumentException("Give the result with the winner.");
        }
        if (p.resultNote() != null) {
            String note = p.resultNote().value();
            if (note != null && note.length() > 120) {
                throw new IllegalArgumentException("A result note is at most 120 characters.");
            }
            set(matchId, "result_note", note == null || note.isBlank() ? null : note.strip());
        }
        jdbc.sql("UPDATE matches SET version = version + 1 WHERE id = :id").param("id", matchId).update();
        return MatchViews.of(loader.load(matchId));
    }

    private void recordResult(MatchData m, String result, Long winnerId) {
        if (!"WIN".equals(result) && !"TIE".equals(result) && !"NO_RESULT".equals(result)) {
            throw new IllegalArgumentException("The result is WIN, TIE or NO_RESULT.");
        }
        if (m.innings().stream().anyMatch(MatchData.Innings::live) || m.status().equals("UPCOMING")) {
            throw conflict("A result can be recorded once no innings is being played.", m);
        }
        if (m.status().equals("ABANDONED")) {
            throw conflict("The match was abandoned.", m);
        }
        if ("WIN".equals(result) && winnerId == null) {
            throw new IllegalArgumentException("A win needs the winning team.");
        }
        if (winnerId != null && !winnerId.equals(m.teamAId()) && !winnerId.equals(m.teamBId())) {
            throw new IllegalArgumentException("The winner must be one of the two teams.");
        }
        if ("NO_RESULT".equals(result) && winnerId != null) {
            throw new IllegalArgumentException("No result has no winner.");
        }
        jdbc.sql("UPDATE matches SET status = 'DONE', result = :result, winner_id = :winner WHERE id = :id")
                .param("result", result)
                .param("winner", winnerId)
                .param("id", m.id())
                .update();
    }

    private void set(long matchId, String column, Object value) {
        // column comes from this class only, never from the request
        jdbc.sql("UPDATE matches SET " + column + " = :v WHERE id = :id").param("v", value).param("id", matchId)
                .update();
    }

    private SideRequest side(long tournamentId, SideRequest s) {
        if (s == null || (s.teamId() == null && (s.label() == null || s.label().isBlank()))) {
            throw new IllegalArgumentException("Each side of a fixture needs a team or a label, e.g. \"Winner SF 1\".");
        }
        if (s.teamId() != null) {
            boolean inTournament = jdbc.sql("SELECT EXISTS (SELECT 1 FROM teams WHERE id = :id AND tournament_id = :t)")
                    .param("id", s.teamId())
                    .param("t", tournamentId)
                    .query(Boolean.class)
                    .single();
            if (!inTournament) {
                throw new IllegalArgumentException("That team is not in this tournament.");
            }
            return new SideRequest(s.teamId(), null);
        }
        String label = s.label().strip();
        if (label.length() > 40) {
            throw new IllegalArgumentException("A label is at most 40 characters.");
        }
        return new SideRequest(null, label);
    }

    private long userByPhone(String phone) {
        String p = phone.replaceAll("\\s", "");
        return jdbc.sql("SELECT id FROM users WHERE phone = :p")
                .param("p", p)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No TurfUp account has the phone " + p + ". The scorer signs up in the app first."));
    }

    private void requireTournament(long tournamentId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM tournaments WHERE id = :id)")
                .param("id", tournamentId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new MatchNotFoundException("tournament", tournamentId);
        }
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException("At most " + max + " characters: \"" + v + "\".");
        }
        return v;
    }

    private static ScoreConflictException conflict(String message, MatchData m) {
        return new ScoreConflictException(message, MatchViews.of(m));
    }
}
