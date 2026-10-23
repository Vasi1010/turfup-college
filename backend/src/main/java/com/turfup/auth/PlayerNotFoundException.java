package com.turfup.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No PLAYER account with this id. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(long playerId) {
        super("Player " + playerId + " not found");
    }
}
