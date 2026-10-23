package com.turfup.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN only (see SecurityConfig): Razorpay refunds needing attention. */
@RestController
@RequestMapping("/api/admin/refund-issues")
class AdminRefundController {

    private final RefundIssueService issues;

    AdminRefundController(RefundIssueService issues) {
        this.issues = issues;
    }

    @GetMapping
    List<RefundIssueService.RefundIssue> list() {
        return issues.list();
    }

    @PostMapping("/{refundId}/settled-manually")
    ResponseEntity<Void> settledManually(@PathVariable long refundId) {
        issues.markSettledManually(refundId);
        return ResponseEntity.noContent().build();
    }
}
