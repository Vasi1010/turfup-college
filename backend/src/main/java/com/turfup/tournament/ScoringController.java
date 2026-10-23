package com.turfup.tournament;

import com.turfup.auth.Role;
import com.turfup.auth.TokenService;
import com.turfup.tournament.ScoringService.Actor;
import com.turfup.tournament.ScoringService.DeliveryRequest;
import com.turfup.tournament.ScoringService.StartInningsRequest;
import com.turfup.tournament.ScoringService.TossRequest;
import com.turfup.tournament.scoring.ExtraType;
import com.turfup.tournament.scoring.WicketType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Live scoring (task B3, docs/tournament.md section 4). Anyone may read a match; the scorer calls need the match's
 * assigned scorer or an admin. Every call returns the whole match. Off unless {@code turfup.tournaments.enabled}.
 */
@RestController
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
class ScoringController {

    record TossBody(@NotNull Long winnerId, @NotNull @Pattern(regexp = "BAT|BOWL") String decision) {
    }

    record StartInningsBody(@NotNull Long strikerId, @NotNull Long nonStrikerId, @NotNull Long bowlerId) {
    }

    /** extraRuns: every extra on the ball, penalty included (a wide with 4 byes is 5). */
    record DeliveryBody(@NotNull UUID id, @NotNull Integer seq, @NotNull Long strikerId, @NotNull Long nonStrikerId,
                        @NotNull Long bowlerId, @NotNull Integer runsBat, ExtraType extraType,
                        @NotNull Integer extraRuns, WicketType wicketType, Long playerOutId, Long fielderId) {
    }

    private final ScoringService scoring;

    ScoringController(ScoringService scoring) {
        this.scoring = scoring;
    }

    /** The matches the caller scores. */
    @GetMapping("/api/scorer/matches")
    List<ScoringService.ScorerMatch> myMatches(@AuthenticationPrincipal Jwt jwt) {
        return scoring.myMatches(actor(jwt));
    }

    @GetMapping("/api/matches/{matchId}")
    MatchView match(@PathVariable long matchId) {
        return scoring.match(matchId);
    }

    @PostMapping("/api/matches/{matchId}/toss")
    MatchView toss(@AuthenticationPrincipal Jwt jwt, @PathVariable long matchId, @Valid @RequestBody TossBody body) {
        return scoring.toss(matchId, actor(jwt), new TossRequest(body.winnerId(), body.decision()));
    }

    @PostMapping("/api/matches/{matchId}/innings")
    MatchView startInnings(@AuthenticationPrincipal Jwt jwt, @PathVariable long matchId,
                           @Valid @RequestBody StartInningsBody body) {
        return scoring.startInnings(matchId, actor(jwt),
                new StartInningsRequest(body.strikerId(), body.nonStrikerId(), body.bowlerId()));
    }

    @PostMapping("/api/innings/{inningsId}/deliveries")
    MatchView addDelivery(@AuthenticationPrincipal Jwt jwt, @PathVariable long inningsId,
                          @Valid @RequestBody DeliveryBody b) {
        return scoring.addDelivery(inningsId, actor(jwt), new DeliveryRequest(b.id(), b.seq(), b.strikerId(),
                b.nonStrikerId(), b.bowlerId(), b.runsBat(), b.extraType(), b.extraRuns(), b.wicketType(),
                b.playerOutId(), b.fielderId()));
    }

    @DeleteMapping("/api/deliveries/{deliveryId}")
    MatchView undo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deliveryId) {
        return scoring.undo(deliveryId, actor(jwt));
    }

    /** Closes the innings early (e.g. rain). The body ({@code {"reason": "CLOSED"}}) is optional. */
    @PostMapping("/api/innings/{inningsId}/end")
    MatchView endInnings(@AuthenticationPrincipal Jwt jwt, @PathVariable long inningsId) {
        return scoring.endInnings(inningsId, actor(jwt));
    }

    private static Actor actor(Jwt jwt) {
        return new Actor(TokenService.userId(jwt), Role.ADMIN.name().equals(jwt.getClaimAsString("role")));
    }
}
