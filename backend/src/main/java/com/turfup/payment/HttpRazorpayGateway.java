package com.turfup.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Razorpay's REST API (https://razorpay.com/docs/api/) with the key id and secret as basic auth. Unused in
 * simulated mode, where it is never called.
 */
@Component
class HttpRazorpayGateway implements RazorpayGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpRazorpayGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    @Autowired
    HttpRazorpayGateway(PaymentsProperties props, RestClient.Builder builder) {
        this(client(props, builder.requestFactory(timeouts())));
    }

    HttpRazorpayGateway(RestClient http) {
        this.http = http;
    }

    /** Razorpay's base URL and basic auth on {@code builder}, keeping its request factory (tests bind a mock server). */
    static RestClient client(PaymentsProperties props, RestClient.Builder builder) {
        PaymentsProperties.Razorpay rz = props.razorpay();
        String credentials = (rz.keyId() == null ? "" : rz.keyId()) + ":" + (rz.keySecret() == null ? "" : rz.keySecret());
        return builder
                .baseUrl(rz.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    @Override
    public String createOrder(long amount, String receipt, Transfer transfer, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("receipt", receipt);
        body.put("notes", notes);
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("account", transfer.account());
        route.put("amount", transfer.amount());
        route.put("currency", "INR");
        route.put("on_hold", transfer.onHoldUntil() != null);
        if (transfer.onHoldUntil() != null) {
            route.put("on_hold_until", transfer.onHoldUntil().getEpochSecond());
        }
        body.put("transfers", List.of(route));
        JsonNode order = call("create order", () -> http.post().uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
        return order.path("id").asText();
    }

    @Override
    public List<GatewayPayment> orderPayments(String orderId) {
        JsonNode list = call("fetch order payments", () -> http.get().uri("/v1/orders/{id}/payments", orderId)
                .retrieve().body(JsonNode.class));
        List<GatewayPayment> payments = new ArrayList<>();
        for (JsonNode p : list.path("items")) {
            payments.add(new GatewayPayment(p.path("id").asText(), p.path("order_id").asText(),
                    p.path("status").asText(), p.path("amount").asLong()));
        }
        return payments;
    }

    @Override
    public GatewayRefund refund(String paymentId, long amount, Map<String, String> notes) {
        Map<String, Object> body = Map.of("amount", amount, "reverse_all", 1, "notes", notes);
        JsonNode refund = call("refund", () -> http.post().uri("/v1/payments/{id}/refund", paymentId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
        return toRefund(refund);
    }

    @Override
    public List<GatewayRefund> refunds(String paymentId) {
        JsonNode list = call("fetch refunds", () -> http.get().uri("/v1/payments/{id}/refunds?count=100", paymentId)
                .retrieve().body(JsonNode.class));
        List<GatewayRefund> refunds = new ArrayList<>();
        for (JsonNode r : list.path("items")) {
            refunds.add(toRefund(r));
        }
        return refunds;
    }

    @Override
    public String accountStatus(String accountId) {
        JsonNode account = call("fetch linked account", () -> http.get().uri("/v2/accounts/{id}", accountId)
                .retrieve().body(JsonNode.class));
        return account.path("status").asText();
    }

    /** Razorpay sends {@code notes} as an object, or as an empty array when there are none. */
    static GatewayRefund toRefund(JsonNode r) {
        Map<String, String> notes = new LinkedHashMap<>();
        r.path("notes").properties().forEach(e -> notes.put(e.getKey(), e.getValue().asText()));
        return new GatewayRefund(r.path("id").asText(), r.path("payment_id").asText(), r.path("amount").asLong(),
                r.path("status").asText(), notes);
    }

    private static JsonNode call(String what, Supplier<JsonNode> request) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new RazorpayException("Razorpay returned an empty response to " + what);
            }
            return body;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.warn("Razorpay {} failed: {} {}", what, e.getStatusCode(), body);
            if (e.getStatusCode().is5xxServerError()) {
                throw new RazorpayException("Payments are not responding right now. Please try again.",
                        RazorpayException.Failure.UNREACHABLE, null, e);
            }
            String description = errorDescription(body);
            boolean notFound = e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                    || body.contains("does not exist");
            throw new RazorpayException("Razorpay could not " + what + ".",
                    notFound ? RazorpayException.Failure.NOT_FOUND : RazorpayException.Failure.REJECTED, description, e);
        } catch (RestClientException e) {
            log.warn("Razorpay {} failed: {}", what, e.toString());
            throw new RazorpayException("Payments are not responding right now. Please try again.",
                    RazorpayException.Failure.UNREACHABLE, null, e);
        }
    }

    /** {@code error.description} of a Razorpay error body ({"error":{"code":..,"description":..}}), else the body. */
    static String errorDescription(String body) {
        try {
            String description = JSON.readTree(body).path("error").path("description").asText("");
            return description.isBlank() ? body : description;
        } catch (JsonProcessingException e) {
            return body;
        }
    }
}
