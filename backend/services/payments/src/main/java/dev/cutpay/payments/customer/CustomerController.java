package dev.cutpay.payments.customer;

import dev.cutpay.payments.customer.CustomerDtos.CreateCustomerRequest;
import dev.cutpay.payments.customer.CustomerDtos.CustomerList;
import dev.cutpay.payments.customer.CustomerDtos.CustomerView;
import dev.cutpay.payments.customer.CustomerDtos.PaymentMethodList;
import dev.cutpay.payments.customer.CustomerDtos.UpdateCustomerRequest;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/v1/customers")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerView create(@AuthenticationPrincipal Jwt jwt,
                        @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                        @Valid @RequestBody CreateCustomerRequest request) {
        return customers.create(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), request);
    }

    @GetMapping("/v1/customers")
    CustomerList list(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                      @RequestParam(name = "q", required = false) String query,
                      @RequestParam(name = "starting_after", required = false) String startingAfter,
                      @RequestParam(defaultValue = "20") int limit) {
        return customers.list(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), query, startingAfter,
                limit);
    }

    @GetMapping("/v1/customers/{id}")
    CustomerView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return customers.get(merchant(jwt), claim(jwt), id);
    }

    @PatchMapping("/v1/customers/{id}")
    CustomerView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                        @Valid @RequestBody UpdateCustomerRequest request) {
        return customers.update(merchant(jwt), claim(jwt), id, request);
    }

    @DeleteMapping("/v1/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        customers.delete(merchant(jwt), claim(jwt), id);
    }

    @GetMapping("/v1/customers/{id}/payment_methods")
    PaymentMethodList paymentMethods(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return customers.paymentMethods(merchant(jwt), claim(jwt), id);
    }

    @DeleteMapping("/v1/customers/{id}/payment_methods/{paymentMethodId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void detach(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String paymentMethodId) {
        customers.detach(merchant(jwt), claim(jwt), id, paymentMethodId);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    /** The mode an API key is limited to, or null for a dashboard session. */
    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}
