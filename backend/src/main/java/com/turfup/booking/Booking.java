package com.turfup.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A booking row. Money fields are paise. Inserts and state changes go through {@link BookingService},
 * which uses SQL so that the database clock and constraints decide the outcome.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "court_id", nullable = false)
    private Long courtId;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "walkin_name")
    private String walkinName;

    @Column(name = "walkin_phone")
    private String walkinPhone;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingSource source;

    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Column(name = "advance_required", nullable = false)
    private long advanceRequired;

    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    @Column(name = "payment_started_at")
    private OffsetDateTime paymentStartedAt;

    @Column(name = "recurring_id")
    private Long recurringId;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancel_note")
    private String cancelNote;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Booking() {
    }

    public Long getId() {
        return id;
    }

    public Long getCourtId() {
        return courtId;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public String getWalkinName() {
        return walkinName;
    }

    public String getWalkinPhone() {
        return walkinPhone;
    }

    public OffsetDateTime getStartAt() {
        return startAt;
    }

    public OffsetDateTime getEndAt() {
        return endAt;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BookingSource getSource() {
        return source;
    }

    public long getTotalPrice() {
        return totalPrice;
    }

    public long getAdvanceRequired() {
        return advanceRequired;
    }

    public OffsetDateTime getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public OffsetDateTime getPaymentStartedAt() {
        return paymentStartedAt;
    }

    public Long getRecurringId() {
        return recurringId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** PLAYER or OWNER when CANCELLED, else null. */
    public String getCancelledBy() {
        return cancelledBy;
    }

    /** RAIN, MAINTENANCE or OTHER when cancelled by the owner, else null. */
    public String getCancelReason() {
        return cancelReason;
    }

    public String getCancelNote() {
        return cancelNote;
    }
}
