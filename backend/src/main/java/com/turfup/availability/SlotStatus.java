package com.turfup.availability;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum SlotStatus {
    AVAILABLE, HELD, BOOKED;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
