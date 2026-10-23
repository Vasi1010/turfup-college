package com.turfup.auth;

public class PhoneAlreadyRegisteredException extends RuntimeException {

    public PhoneAlreadyRegisteredException() {
        super("An account with this phone number already exists. Please log in.");
    }
}
