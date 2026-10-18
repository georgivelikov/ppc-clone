package dev.cutpay.payments.customer;

import dev.cutpay.payments.domain.Customer;
import dev.cutpay.payments.domain.PaymentMethod;
import dev.cutpay.payments.domain.SetupIntent;
import dev.cutpay.payments.payment.PaymentDtos.NextAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            @Size(max = 254) String email,
            @Size(max = 160) String name,
            @Size(max = 40) String phone,
            @Size(max = 500) String description,
            Map<String, String> address,
            Map<String, String> metadata) {
    }

    public record UpdateCustomerRequest(
            @Size(max = 254) String email,
            @Size(max = 160) String name,
            @Size(max = 40) String phone,
            @Size(max = 500) String description,
            Map<String, String> address,
            Map<String, String> metadata,
            String defaultPaymentMethod) {
    }

    public record CustomerView(String id, String object, boolean livemode, String email, String name, String phone,
                               String description, Map<String, String> address, Map<String, String> metadata,
                               String defaultPaymentMethod, Instant createdAt) {
        public static CustomerView of(Customer c) {
            return new CustomerView(c.getId(), "customer", c.isLivemode(), c.getEmail(), c.getName(), c.getPhone(),
                    c.getDescription(), c.getAddress(), c.getMetadata(), c.getDefaultPaymentMethod(), c.getCreatedAt());
        }
    }

    public record CustomerList(String object, List<CustomerView> data, boolean hasMore) {
    }

    public record CardView(String brand, String last4, Integer expMonth, Integer expYear, String fingerprint) {
    }

    public record PaymentMethodView(String id, String object, String type, String customer, boolean livemode,
                                    CardView card, boolean isDefault, Instant createdAt) {
        public static PaymentMethodView of(PaymentMethod m, boolean isDefault) {
            return new PaymentMethodView(m.getId(), "payment_method", m.getType(), m.getCustomerId(), m.isLivemode(),
                    new CardView(m.getCardBrand(), m.getCardLast4(), m.getCardExpMonth(), m.getCardExpYear(),
                            m.getCardFingerprint()),
                    isDefault, m.getCreatedAt());
        }
    }

    public record PaymentMethodList(String object, List<PaymentMethodView> data) {
    }

    public record CreateSetupIntentRequest(@NotBlank String customer, @Size(max = 500) String description,
                                           @Size(max = 2000) String returnUrl, @Size(max = 160) String merchantName) {
    }

    public record SetupIntentView(String id, String object, boolean livemode, String customer, String status,
                                  String clientSecret, String url, String paymentMethod, String description,
                                  String returnUrl, String failureCode, String failureMessage, Instant createdAt) {
        public static SetupIntentView of(SetupIntent s, String checkoutUrl) {
            return new SetupIntentView(s.getId(), "setup_intent", s.isLivemode(), s.getCustomerId(),
                    s.getStatus().wire(), s.getClientSecret(), checkoutUrl + "/s/" + s.getClientSecret(),
                    s.getPaymentMethodId(), s.getDescription(), s.getReturnUrl(), s.getFailureCode(),
                    s.getFailureMessage(), s.getCreatedAt());
        }
    }

    /** What the hosted card page may see. */
    public record SetupSessionView(String id, String status, String merchantName, String description,
                                   String returnUrl, String failureCode, String failureMessage,
                                   NextAction nextAction) {
    }


    public record ConfirmSetupRequest(@NotBlank String token) {
    }
}
