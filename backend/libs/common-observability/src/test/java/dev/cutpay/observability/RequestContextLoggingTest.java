package dev.cutpay.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerMapping;

class RequestContextLoggingTest {

    private final RequestContextLogging interceptor = new RequestContextLogging();

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private static Jwt token(String merchant) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("mid", merchant));
    }

    @Test
    void theMerchantFromTheVerifiedTokenAndThePaymentFromThePathAreInTheLogContext() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(token("mer_1"), null));
        var request = new MockHttpServletRequest("GET", "/v1/payment_intents/pay_9");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "pay_9"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("merchant_id")).isEqualTo("mer_1");
        assertThat(MDC.get("payment_intent_id")).isEqualTo("pay_9");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(MDC.get("merchant_id")).isNull();
        assertThat(MDC.get("payment_intent_id")).isNull();
    }

    @Test
    void anIdThatIsNotAPaymentIsNotLoggedAsOne() {
        var request = new MockHttpServletRequest("GET", "/v1/payment_intents/anything");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "anything\nforged line"));
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertThat(MDC.get("payment_intent_id")).isNull();
    }

    @Test
    void anonymousRequestsAddNothing() {
        interceptor.preHandle(new MockHttpServletRequest("GET", "/checkout/sessions/x"), new MockHttpServletResponse(), new Object());
        assertThat(MDC.getCopyOfContextMap()).isNull();
    }
}
