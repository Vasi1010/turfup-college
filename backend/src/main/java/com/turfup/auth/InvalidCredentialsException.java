package com.turfup.auth;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Wrong phone number or password.");
    }
}
