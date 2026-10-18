package dev.cutpay.payments.customer;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.payments.acquirer.AcquirerClient.CardInfo;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.customer.CustomerDtos.CreateCustomerRequest;
import dev.cutpay.payments.customer.CustomerDtos.CustomerList;
import dev.cutpay.payments.customer.CustomerDtos.CustomerView;
import dev.cutpay.payments.customer.CustomerDtos.PaymentMethodList;
import dev.cutpay.payments.customer.CustomerDtos.PaymentMethodView;
import dev.cutpay.payments.customer.CustomerDtos.UpdateCustomerRequest;
import dev.cutpay.payments.domain.Customer;
import dev.cutpay.payments.domain.PaymentMethod;
import dev.cutpay.payments.domain.Repositories.CustomerRepository;
import dev.cutpay.payments.domain.Repositories.PaymentMethodRepository;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers and their saved cards. A saved card is only a pointer to the vault plus what is safe to display, the
 * card number never reaches this service.
 */
@Service
public class CustomerService {

    private static final Set<String> ADDRESS_KEYS = Set.of("line1", "line2", "city", "postal_code", "country");
    private static final Pattern EMAIL = Pattern.compile("^\\S+@\\S+\\.\\S+$");

    private final CustomerRepository customers;
    private final PaymentMethodRepository methods;
    private final OutboxWriter outbox;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, PaymentMethodRepository methods, OutboxWriter outbox,
                           Clock clock) {
        this.customers = customers;
        this.methods = methods;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public CustomerView create(String merchantId, boolean livemode, CreateCustomerRequest request) {
        validateEmail(request.email());
        var customer = new Customer(Ids.generate("cus"), merchantId, livemode, clean(request.email()),
                clean(request.name()), clean(request.phone()), clean(request.description()),
                address(request.address()), request.metadata(), clock.instant());
        customers.save(customer);
        publish("CustomerCreated", customer);
        return CustomerView.of(customer);
    }

    @Transactional(readOnly = true)
    public CustomerView get(String merchantId, Boolean claimedMode, String id) {
        return CustomerView.of(find(merchantId, claimedMode, id));
    }

    @Transactional(readOnly = true)
    public CustomerList list(String merchantId, boolean livemode, String query, String startingAfter, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        String after = startingAfter == null || startingAfter.isBlank() ? null : startingAfter;
        var found = customers.page(merchantId, livemode, after, like, PageRequest.of(0, size + 1));
        return new CustomerList("list", found.stream().limit(size).map(CustomerView::of).toList(), found.size() > size);
    }

    @Transactional
    public CustomerView update(String merchantId, Boolean claimedMode, String id, UpdateCustomerRequest request) {
        Customer customer = find(merchantId, claimedMode, id);
        validateEmail(request.email());
        customer.update(clean(request.email()), clean(request.name()), clean(request.phone()),
                clean(request.description()), address(request.address()), request.metadata(), clock.instant());
        if (request.defaultPaymentMethod() != null) {
            PaymentMethod method = requireUsable(customer, request.defaultPaymentMethod());
            customer.setDefaultPaymentMethod(method.getId(), clock.instant());
        }
        publish("CustomerUpdated", customer);
        return CustomerView.of(customer);
    }

    /** Hides the customer and detaches their cards. Their payments, invoices and history remain. */
    @Transactional
    public void delete(String merchantId, Boolean claimedMode, String id) {
        Customer customer = find(merchantId, claimedMode, id);
        for (PaymentMethod method : methods.attached(id)) {
            method.detach(clock.instant());
            publish("PaymentMethodDetached", customer, method);
        }
        customer.delete(clock.instant());
        publish("CustomerDeleted", customer);
    }

    // --- saved cards -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentMethodList paymentMethods(String merchantId, Boolean claimedMode, String customerId) {
        Customer customer = find(merchantId, claimedMode, customerId);
        return new PaymentMethodList("list", methods.attached(customerId).stream()
                .map(m -> PaymentMethodView.of(m, m.getId().equals(customer.getDefaultPaymentMethod()))).toList());
    }

    @Transactional
    public void detach(String merchantId, Boolean claimedMode, String customerId, String paymentMethodId) {
        Customer customer = find(merchantId, claimedMode, customerId);
        PaymentMethod method = requireUsable(customer, paymentMethodId);
        method.detach(clock.instant());
        if (paymentMethodId.equals(customer.getDefaultPaymentMethod())) {
            // Another saved card takes over, so a subscription does not silently lose its payment method.
            String next = methods.attached(customerId).stream().filter(m -> !m.getId().equals(paymentMethodId))
                    .map(PaymentMethod::getId).findFirst().orElse(null);
            customer.setDefaultPaymentMethod(next, clock.instant());
        }
        publish("PaymentMethodDetached", customer, method);
    }

    /**
     * Saves a card the customer has just used or verified. The same physical card (fingerprint and expiry) is saved
     * once, and it becomes the default when the customer had no card yet.
     */
    @Transactional
    public PaymentMethod saveCard(String merchantId, String customerId, CardInfo card, String token) {
        Customer customer = customers.lockById(customerId).orElseThrow();
        if (customer.isDeleted() || !customer.getMerchantId().equals(merchantId)) {
            throw ApiException.notFound("customer_not_found", "No such customer");
        }
        var existing = methods.sameCard(customerId, card.fingerprint(), card.expMonth(), card.expYear());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        var method = new PaymentMethod(Ids.generate("pm"), merchantId, customer.isLivemode(), customerId,
                card.brand(), card.last4(), card.expMonth(), card.expYear(), card.fingerprint(), card.cardType(),
                card.cardRegion(), token, clock.instant());
        methods.save(method);
        if (customer.getDefaultPaymentMethod() == null) {
            customer.setDefaultPaymentMethod(method.getId(), clock.instant());
        }
        publish("PaymentMethodAttached", customer, method);
        return method;
    }

    /** The saved card to charge: the one asked for, else the default of the customer. */
    @Transactional(readOnly = true)
    public PaymentMethod chargeable(String merchantId, Boolean claimedMode, String customerId, String paymentMethodId) {
        Customer customer = find(merchantId, claimedMode, customerId);
        String id = paymentMethodId != null ? paymentMethodId : customer.getDefaultPaymentMethod();
        if (id == null) {
            throw ApiException.invalidRequest("no_payment_method", "This customer has no saved card", "payment_method");
        }
        return requireUsable(customer, id);
    }

    /** A saved card by id, for charging it. */
    @Transactional(readOnly = true)
    public PaymentMethod method(String paymentMethodId) {
        return methods.findById(paymentMethodId)
                .orElseThrow(() -> ApiException.notFound("payment_method_not_found", "No such saved card"));
    }

    public Customer find(String merchantId, Boolean claimedMode, String id) {
        Customer customer = customers.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId)
                .orElseThrow(() -> ApiException.notFound("customer_not_found", "No such customer"));
        if (claimedMode != null && claimedMode != customer.isLivemode()) {
            throw ApiException.notFound("customer_not_found", "No such customer");
        }
        return customer;
    }

    private PaymentMethod requireUsable(Customer customer, String paymentMethodId) {
        return methods.findByIdAndCustomerId(paymentMethodId, customer.getId()).filter(m -> !m.isDetached())
                .orElseThrow(() -> ApiException.invalidRequest("payment_method_not_found",
                        "No such saved card for this customer", "payment_method"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateEmail(String email) {
        if (email != null && !email.isBlank() && !EMAIL.matcher(email.trim()).matches()) {
            throw ApiException.invalidRequest("invalid_email", "Enter a valid email address", "email");
        }
    }

    private static Map<String, String> address(Map<String, String> address) {
        if (address == null) {
            return null;
        }
        for (String key : address.keySet()) {
            if (!ADDRESS_KEYS.contains(key)) {
                throw ApiException.invalidRequest("invalid_address",
                        "Address can only contain line1, line2, city, postal_code and country", "address");
            }
        }
        return Map.copyOf(address);
    }

    private void publish(String eventType, Customer customer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_id", customer.getId());
        payload.put("email", customer.getEmail());
        payload.put("name", customer.getName());
        outbox.write(KafkaTopicsConfig.CUSTOMER_TOPIC, eventType, customer.getId(), customer.getMerchantId(),
                customer.isLivemode(), payload);
    }

    private void publish(String eventType, Customer customer, PaymentMethod method) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_method_id", method.getId());
        payload.put("customer_id", customer.getId());
        payload.put("brand", method.getCardBrand());
        payload.put("last4", method.getCardLast4());
        outbox.write(KafkaTopicsConfig.CUSTOMER_TOPIC, eventType, customer.getId(), customer.getMerchantId(),
                customer.isLivemode(), payload);
    }
}
