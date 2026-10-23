package com.turfup.availability;

import java.time.OffsetDateTime;

/** One slot as shown to players. {@code price} is in paise; times are IST. */
public record SlotView(OffsetDateTime start, OffsetDateTime end, long price, SlotStatus status) {
}
