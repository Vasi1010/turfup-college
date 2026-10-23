package com.turfup.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.turfup.auth.TokenService;
import com.turfup.tournament.TournamentAdminService.AddTeam;
import com.turfup.tournament.TournamentAdminService.Change;
import com.turfup.tournament.TournamentAdminService.CreateMatch;
import com.turfup.tournament.TournamentAdminService.CreateTournament;
import com.turfup.tournament.TournamentAdminService.MatchPatch;
import com.turfup.tournament.TournamentAdminService.SideRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/**
 * Tournament pages (task B5, public) and setting a tournament up (task B5a, ADMIN; the role is checked in
 * SecurityConfig). Off unless {@code turfup.tournaments.enabled}.
 */
@RestController
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
class TournamentController {

    private final TournamentQueryService query;
    private final TournamentAdminService admin;

    TournamentController(TournamentQueryService query, TournamentAdminService admin) {
        this.query = query;
        this.admin = admin;
    }

    @GetMapping("/api/tournaments")
    List<TournamentQueryService.Summary> list() {
        return query.list();
    }

    @GetMapping("/api/tournaments/{id}")
    TournamentView.Detail detail(@PathVariable long id) {
        return query.detail(id);
    }

    @GetMapping("/api/tournaments/{id}/table")
    TournamentView.Table table(@PathVariable long id) {
        return query.table(id);
    }

    @GetMapping("/api/tournaments/{id}/leaderboards")
    TournamentView.Leaders leaderboards(@PathVariable long id) {
        return query.leaderboards(id);
    }

    /** ADMIN (the /api/admin/** rule): who scores each match, with phones. */
    @GetMapping("/api/admin/tournaments/{id}/scorers")
    List<TournamentAdminService.MatchScorer> scorers(@PathVariable long id) {
        return admin.scorers(id);
    }

    @PostMapping("/api/tournaments")
    @ResponseStatus(HttpStatus.CREATED)
    TournamentView.Detail create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateTournament req) {
        return admin.createTournament(TokenService.userId(jwt), req);
    }

    @PostMapping("/api/tournaments/{id}/teams")
    @ResponseStatus(HttpStatus.CREATED)
    TournamentView.Team addTeam(@PathVariable long id, @RequestBody AddTeam req) {
        return admin.addTeam(id, req);
    }

    @PostMapping("/api/tournaments/{id}/matches")
    @ResponseStatus(HttpStatus.CREATED)
    MatchView createMatch(@PathVariable long id, @RequestBody CreateMatch req) {
        return admin.createMatch(id, req);
    }

    /** Only the fields sent change; a field sent as null clears it. */
    @PatchMapping("/api/matches/{id}")
    MatchView updateMatch(@PathVariable long id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Send the fields to change as a JSON object.");
        }
        return admin.updateMatch(id, new MatchPatch(
                change(body, "scheduledAt", n -> {
                    try {
                        return OffsetDateTime.parse(n.asText());
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("scheduledAt must be a date and time with an offset.");
                    }
                }),
                change(body, "teamA", TournamentController::side),
                change(body, "teamB", TournamentController::side),
                change(body, "oversPerInnings", n -> integer(n, "oversPerInnings")),
                change(body, "scorerPhone", JsonNode::asText),
                change(body, "status", JsonNode::asText),
                change(body, "result", JsonNode::asText),
                change(body, "winnerId", n -> id(n, "winnerId")),
                change(body, "resultNote", JsonNode::asText)));
    }

    private static <T> Change<T> change(JsonNode body, String field, Function<JsonNode, T> read) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode n = body.get(field);
        return new Change<>(n.isNull() ? null : read.apply(n));
    }

    private static SideRequest side(JsonNode n) {
        if (!n.isObject()) {
            throw new IllegalArgumentException("A side is {\"teamId\": ...} or {\"label\": ...}.");
        }
        JsonNode id = n.get("teamId");
        JsonNode label = n.get("label");
        return new SideRequest(id == null || id.isNull() ? null : id(id, "teamId"),
                label == null || label.isNull() ? null : label.asText());
    }

    private static long id(JsonNode n, String field) {
        if (!n.canConvertToLong() || !n.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an id.");
        }
        return n.asLong();
    }

    private static int integer(JsonNode n, String field) {
        if (!n.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a whole number.");
        }
        return n.asInt();
    }
}
