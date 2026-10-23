package com.turfup.venue;

/**
 * A venue can be approved only once it can take advances: in razorpay mode its owner's Razorpay linked account is
 * activated, in upi mode it has a UPI ID.
 */
public class VenueApprovalBlockedException extends RuntimeException {

    public VenueApprovalBlockedException(String accountStatus) {
        super(accountStatus == null
                ? "Link the owner's Razorpay account before approving this venue."
                : "The owner's Razorpay account is " + accountStatus.replace('_', ' ')
                  + ". Approve the venue once Razorpay has activated it.");
    }

    private VenueApprovalBlockedException() {
        super("The owner has not added a UPI ID for advances yet. Approve the venue once it is set in venue setup.");
    }

    public static VenueApprovalBlockedException upiIdMissing() {
        return new VenueApprovalBlockedException();
    }
}
