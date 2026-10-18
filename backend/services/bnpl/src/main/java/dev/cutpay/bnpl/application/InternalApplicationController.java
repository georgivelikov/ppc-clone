package dev.cutpay.bnpl.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service to service: payments asks for a refund of a payment that an application financed. The gateway has no route to
 * {@code /internal/**}, so it is reachable only inside the platform network.
 */
@RestController
class InternalApplicationController {

    record RefundRequest(@NotBlank String refundId, @Min(1) long amount) {
    }

    record RefundResult(String status) {
    }

    private final ApplicationService service;

    InternalApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/internal/applications/{id}/refunds")
    RefundResult refund(@PathVariable String id, @Valid @RequestBody RefundRequest request) {
        service.refund(id, request.refundId(), request.amount());
        return new RefundResult("succeeded");
    }
}
