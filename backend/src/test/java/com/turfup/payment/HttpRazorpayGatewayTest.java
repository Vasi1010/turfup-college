package com.turfup.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The requests TurfUp sends to Razorpay, checked against a mock server (no network, no Spring context). */
class HttpRazorpayGatewayTest {

    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("rzp_test_k:secret".getBytes(StandardCharsets.UTF_8));

    MockRestServiceServer server;
    HttpRazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentsProperties props = new PaymentsProperties(PaymentMode.RAZORPAY,
                new PaymentsProperties.Razorpay("rzp_test_k", "secret", "whsec", null));
        gateway = new HttpRazorpayGateway(HttpRazorpayGateway.client(props, builder));
    }

    @Test
    void orderCarriesTheRouteTransferHeldUntilTheRefundDeadline() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(content().json("""
                        {"amount":20000,"currency":"INR","receipt":"booking-7","notes":{"booking_id":"7"},
                         "transfers":[{"account":"acc_Owner000000001","amount":20000,"currency":"INR",
                                       "on_hold":true,"on_hold_until":1894548600}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"order_X1\",\"status\":\"created\"}", MediaType.APPLICATION_JSON));

        String id = gateway.createOrder(20_000, "booking-7",
                new RazorpayGateway.Transfer("acc_Owner000000001", 20_000, Instant.ofEpochSecond(1_894_548_600)),
                Map.of("booking_id", "7"));

        assertThat(id).isEqualTo("order_X1");
        server.verify();
    }

    @Test
    void anUnheldTransferSettlesOnTheNormalSchedule() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andExpect(content().json("""
                        {"transfers":[{"account":"acc_Owner000000001","amount":20000,"currency":"INR","on_hold":false}]}
                        """, JsonCompareMode.LENIENT))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).doesNotContain("on_hold_until"))
                .andRespond(withSuccess("{\"id\":\"order_X2\"}", MediaType.APPLICATION_JSON));

        gateway.createOrder(20_000, "booking-8", new RazorpayGateway.Transfer("acc_Owner000000001", 20_000, null),
                Map.of());

        server.verify();
    }

    @Test
    void aRefundRazorpayRefusesIsRejectedWithItsReason() {
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_P1/refund"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"Insufficient balance in linked account"}}
                        """));

        assertThatThrownBy(() -> gateway.refund("pay_P1", 20_000, Map.of()))
                .isInstanceOfSatisfying(RazorpayException.class, e -> {
                    assertThat(e.failure()).isEqualTo(RazorpayException.Failure.REJECTED);
                    assertThat(e.detail()).isEqualTo("Insufficient balance in linked account");
                });
    }

    @Test
    void aServerErrorIsUnreachableNotRejected() {
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_P1/refund"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.refund("pay_P1", 20_000, Map.of()))
                .isInstanceOfSatisfying(RazorpayException.class, e -> assertThat(e.rejected()).isFalse());
    }

    @Test
    void refundReversesTheTransfer() {
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_P1/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"amount":20000,"reverse_all":1,"notes":{"turfup_refund_id":"42"}}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"id":"rfnd_R1","payment_id":"pay_P1","amount":20000,"status":"pending",
                         "notes":{"turfup_refund_id":"42"}}
                        """, MediaType.APPLICATION_JSON));

        RazorpayGateway.GatewayRefund refund = gateway.refund("pay_P1", 20_000, Map.of("turfup_refund_id", "42"));

        assertThat(refund).isEqualTo(new RazorpayGateway.GatewayRefund("rfnd_R1", "pay_P1", 20_000, "pending",
                Map.of("turfup_refund_id", "42")));
    }

    @Test
    void emptyNotesArriveAsAnArray() {
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_P1/refunds?count=100"))
                .andRespond(withSuccess("""
                        {"entity":"collection","count":1,"items":[
                          {"id":"rfnd_R9","payment_id":"pay_P1","amount":500,"status":"processed","notes":[]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.refunds("pay_P1")).singleElement()
                .satisfies(r -> assertThat(r.notes()).isEmpty());
    }

    @Test
    void orderPaymentsAreListed() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_X1/payments"))
                .andRespond(withSuccess("""
                        {"entity":"collection","count":2,"items":[
                          {"id":"pay_A","order_id":"order_X1","status":"failed","amount":20000},
                          {"id":"pay_B","order_id":"order_X1","status":"captured","amount":20000}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.orderPayments("order_X1")).extracting(RazorpayGateway.GatewayPayment::status)
                .containsExactly("failed", "captured");
    }

    @Test
    void unknownLinkedAccountIsReportedAsNotFound() {
        server.expect(requestTo("https://api.razorpay.com/v2/accounts/acc_Missing0000001"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist"}}
                        """));

        assertThatThrownBy(() -> gateway.accountStatus("acc_Missing0000001"))
                .isInstanceOfSatisfying(RazorpayException.class, e -> assertThat(e.notFound()).isTrue());
    }

    @Test
    void razorpayModeRefusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new PaymentsProperties(PaymentMode.RAZORPAY,
                new PaymentsProperties.Razorpay("rzp_test_k", "secret", "", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
        // Simulated mode needs none.
        assertThat(new PaymentsProperties(null, null).mode()).isEqualTo(PaymentMode.SIMULATED);
    }
}
