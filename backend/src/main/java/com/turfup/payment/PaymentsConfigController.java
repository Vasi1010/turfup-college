package com.turfup.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: what the frontend needs to know about how payments work here. */
@RestController
class PaymentsConfigController {

    record ClientConfig(PaymentMode paymentsMode) {
    }

    private final PaymentsProperties props;

    PaymentsConfigController(PaymentsProperties props) {
        this.props = props;
    }

    @GetMapping("/api/config")
    ClientConfig config() {
        return new ClientConfig(props.mode());
    }
}
