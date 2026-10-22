package dev.cutpay.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Puts who and what a request is about into the log context, so every log line of the request (and, through the
 * JSON log format, every field of it) can be found by {@code merchant_id} or {@code payment_intent_id} in Loki, not
 * only by trace id. It runs after authentication, so the merchant comes from the verified token and never from the
 * request. Nothing personal goes in: ids only.
 */
public class RequestContextLogging implements HandlerInterceptor {

    static final String MERCHANT = "merchant_id";
    static final String PAYMENT = "payment_intent_id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String merchant = jwt.getClaimAsString("mid");
            if (merchant != null) {
                MDC.put(MERCHANT, merchant);
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars != null && request.getRequestURI().contains("/payment_intents/")) {
            String id = vars.get("id");
            if (id != null && id.startsWith("pay_")) {
                MDC.put(PAYMENT, id);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(MERCHANT);
        MDC.remove(PAYMENT);
    }
}
