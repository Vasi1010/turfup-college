package com.turfup.payment;

/** Razorpay refused a request or could not be reached. The message is safe to show the user. */
public class RazorpayException extends RuntimeException {

    public enum Failure {
        /** No answer, a timeout, or a 5xx: worth retrying as is. */
        UNREACHABLE,
        /** Razorpay answered with a 4xx: it will keep refusing until something changes on its side. */
        REJECTED,
        /** A 4xx because the thing asked about does not exist. */
        NOT_FOUND
    }

    private final Failure failure;
    private final String detail;

    /**
     * @param detail Razorpay's own description of the error, if it gave one (for logs and the admin)
     */
    public RazorpayException(String message, Failure failure, String detail, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.detail = detail;
    }

    public RazorpayException(String message) {
        this(message, Failure.UNREACHABLE, null, null);
    }

    public Failure failure() {
        return failure;
    }

    public boolean notFound() {
        return failure == Failure.NOT_FOUND;
    }

    /** Razorpay answered and refused (including NOT_FOUND). */
    public boolean rejected() {
        return failure != Failure.UNREACHABLE;
    }

    /** Razorpay's description of the error, or the message if it gave none. */
    public String detail() {
        return detail == null || detail.isBlank() ? getMessage() : detail;
    }
}
